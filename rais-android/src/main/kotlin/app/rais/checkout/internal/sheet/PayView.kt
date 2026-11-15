package app.rais.checkout.internal.sheet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.text.method.PasswordTransformationMethod
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import app.rais.checkout.R

/** The keys a card field takes: digits of any script (the formatter writes them as 0-9) and the separators it writes itself. */
private const val CARD_KEYS = "0123456789 /٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹"

/** A number pad for a field whose text the SDK writes with separators ("6280 5811…", "12 / 27"). */
internal fun EditText.numberPad(secret: Boolean = false) {
    keyListener = DigitsKeyListener.getInstance(CARD_KEYS)
    setRawInputType(InputType.TYPE_CLASS_NUMBER or if (secret) InputType.TYPE_NUMBER_VARIATION_PASSWORD else 0)
    if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
}

internal fun EditText.autofill(vararg hints: String) {
    if (Build.VERSION.SDK_INT >= 26) {
        setAutofillHints(*hints)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
    }
}

/**
 * The payment to pay: who is paid and how much, then the ways to pay it (the payer's Raïs balance and cards when the checkout knows who
 * they are, the cards a store keeps for its customer, a card typed now) and whether a typed card is kept. One button, owned by the screen,
 * pays with whatever is chosen here. The same page as rais-ios `PayView`.
 */
@SuppressLint("ViewConstructor")
internal class PayView(
    context: Context,
    private val sheetContext: SheetContext,
    sheet: Sheet,
    private val payment: Sheet.Payment,
    private val pay: Sheet.Pay,
) : Stack(context, vertical = true, gap = sheetContext.theme.dp(12)) {
    sealed class Choice {
        object Balance : Choice()
        data class Card(val card: Sheet.Pay.Card) : Choice()
        object NewCard : Choice()

        /** A name for a way to pay (a key for what hangs off it). */
        val key: String
            get() = when (this) {
                Balance -> "balance"
                is Card -> "card.${card.id}"
                NewCard -> "new"
            }
    }

    class Submission(val method: CheckoutApi.Method, val email: String?)

    private val theme get() = sheetContext.theme
    private val words get() = sheetContext.words

    private val avatar = AvatarView(context, sheetContext.theme, sheet.merchant?.name ?: "")
    private var detailsBox: View? = null
    private var detailsButton: SheetButton? = null
    private val choiceRows = mutableListOf<Pair<Choice, ChoiceRow>>()
    private val newCardSection = Stack(context, vertical = true, gap = sheetContext.theme.dp(12))
    private val brands = BrandMarks(context, sheetContext.theme)
    private val panCell = InputCell(context, sheetContext.theme, sheetContext.words["pay.card_number"], "1234 1234 1234 1234", accessory = brands)
    private val expiryCell = InputCell(context, sheetContext.theme, sheetContext.words["pay.expiry"], sheetContext.words["pay.expiry_hint"])
    private val cvcCell = InputCell(context, sheetContext.theme, sheetContext.words["pay.cvc"], "•••")
    private val nameCell = InputCell(context, sheetContext.theme, sheetContext.words["pay.name"])
    private val cardGroup = InputGroup(context, sheetContext.theme, listOf(listOf(panCell), listOf(expiryCell, cvcCell)))
    private val nameGroup = InputGroup(context, sheetContext.theme, listOf(listOf(nameCell)))
    private val savedCvcCell = InputCell(context, sheetContext.theme, sheetContext.words["saved.cvc"], "•••")
    private val savedCvcGroup = InputGroup(context, sheetContext.theme, listOf(listOf(savedCvcCell)))
    private var emailCell: InputCell? = null
    private var emailGroup: InputGroup? = null
    private var saveRow: ToggleRow? = null
    private var appRow: RaisPayRow? = null
    /** The hairline above each way to pay (hidden with the saved ones), and the way to bring the saved ones back after "another card" folded them away. */
    private val separators = mutableMapOf<String, View>()
    private var newCardRow: ChoiceRow? = null
    private var newCardGlyph: Glyph? = null
    private var choicesHeading: Label? = null
    /** Whether the saved ways to pay (balance, saved cards) are folded away: see [foldedAfter]. */
    var savedFolded = false
        private set

    var choice: Choice = Choice.NewCard
        private set

    /** The chosen way to pay changed (the button's words follow it). */
    var onChoice: (() -> Unit)? = null
    /** The payer is about to type a card: time to get a session with the bank ready. */
    var onCardEditing: (() -> Unit)? = null
    /** A field took the focus: the screen brings this view (the field and what is said under it) into view. */
    var onFocus: ((View) -> Unit)? = null
    var onSignOut: (() -> Unit)? = null
    var onOpen: ((String) -> Unit)? = null
    var onOpenApp: ((String) -> Unit)? = null

    fun showLogo(bitmap: Bitmap, animated: Boolean = true) = avatar.show(bitmap, animated)
    fun showLogo(@DrawableRes drawable: Int) = avatar.show(drawable)

    init {
        buildSummary(sheet)
        buildRaisRow()
        buildChoices()
        buildCardForm()
        buildAgreements()
        select(initialChoice(), animated = false)
    }

    // ── What is being paid ──

    private fun buildSummary(sheet: Sheet) {
        val summary = Stack(context, vertical = true)
        summary.gravity = Gravity.CENTER_HORIZONTAL
        summary.add(avatar, width = theme.dp(64), height = theme.dp(64), gravity = Gravity.CENTER_HORIZONTAL)
        val name = Label(context, theme, 17f, 600).centered()
        name.text = sheet.merchant?.name
        name.maxLines = 2
        name.contentDescription = words["pay.pay_to"]
        summary.add(name, before = theme.dp(10))
        val website = sheet.merchant?.website
        if (!website.isNullOrEmpty()) {
            val host = Label(context, theme, 13f) { theme.secondary }.centered()
            host.text = website
            summary.add(host, before = theme.dp(2))
        }
        val amount = AmountLabel(context, theme, payment.amountNumber, payment.currency, 44f, payment.amountText)
        summary.add(amount, before = theme.dp(14))
        val description = payment.description
        if (!description.isNullOrEmpty()) {
            val text = Label(context, theme, 15f) { theme.secondary }.centered()
            text.text = description
            text.maxLines = 3
            summary.add(text, before = theme.dp(4))
        }
        add(summary)

        if (payment.lines.isEmpty() && payment.details.isEmpty()) return
        val toggle = SheetButton(context, theme, SheetButton.Kind.Plain, words["pay.show_details"], R.drawable.rais_ic_expand_more)
        toggle.tag = "rais.details"
        toggle.onTap { toggleDetails() }
        add(toggle, before = 0, width = WRAP, gravity = Gravity.CENTER_HORIZONTAL)
        detailsButton = toggle

        val details = Stack(context, vertical = true, gap = theme.dp(12))
        if (payment.lines.isNotEmpty()) {
            val box = GroupBox(context, theme)
            for (line in payment.lines) {
                box.add(lineRow(line.name, listOfNotNull(line.description, line.quantity).joinToString("\n"), line.totalText))
            }
            payment.adjustmentText?.let { box.add(lineRow(words["receipt.adjustments"], "", it)) }
            box.add(lineRow(words["pay.total"], "", payment.amountText, strong = true))
            details.add(box)
        }
        if (payment.details.isNotEmpty()) details.add(rowsCard(sheetContext, context, payment.details))
        details.visibility = GONE
        add(details, before = theme.dp(4))
        detailsBox = details
    }

    private fun lineRow(title: String, detail: String, value: String, strong: Boolean = false): View {
        val name = Label(context, theme, 15f, if (strong) 600 else 500)
        name.text = title
        val sub = Label(context, theme, 13f, digits = true) { theme.secondary }
        sub.text = detail
        sub.visibility = if (detail.isEmpty()) GONE else VISIBLE
        val left = Stack(context, vertical = true)
        left.add(name)
        left.add(sub, before = theme.dp(2))
        val total = Label(context, theme, 15f, if (strong) 600 else 500, digits = true)
        total.text = value
        total.textAlignment = TEXT_ALIGNMENT_VIEW_END
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(theme.dp(14), theme.dp(12), theme.dp(14), theme.dp(12))
        }
        row.addView(left, LayoutParams(0, WRAP, 1f))
        row.addView(total, LayoutParams(WRAP, WRAP).apply { marginStart = theme.dp(16) })
        return row
    }

    private fun toggleDetails() {
        val box = detailsBox ?: return
        val button = detailsButton ?: return
        val show = box.visibility != VISIBLE
        button.title = words[if (show) "pay.hide_details" else "pay.show_details"]
        button.icon = if (show) R.drawable.rais_ic_expand_less else R.drawable.rais_ic_expand_more
        animateChanges()
        box.visibility = if (show) VISIBLE else GONE
        // (as the stack on iOS: 4 under the button, then 24 under the open details)
        nextAfter(box)?.let { gapBefore(it, if (show) theme.dp(24) else theme.dp(4)) }
    }

    private fun nextAfter(view: View): View? {
        val index = indexOfChild(view)
        return if (index >= 0 && index + 1 < childCount) getChildAt(index + 1) else null
    }

    private fun animateChanges() {
        (rootView as? ViewGroup)?.let { TransitionManager.beginDelayedTransition(it, AutoTransition().setDuration(220)) }
    }

    // ── The ways to pay ──

    /** "Pay with Raïs", first, for a payer nobody identified: the Raïs app confirms it with the payer's balance or a saved card. */
    private fun buildRaisRow() {
        val appUrl = pay.appUrl
        if (pay.payer != null || appUrl == null) return
        val brand = words["wallet.cta"]
        val note = words["sheet.app_note"]
        // A server that does not word this yet (it is older than the SDK) leaves the card form as it always was
        if (brand.isEmpty() || note.isEmpty()) return
        val row = RaisPayRow(context, theme, brand, note)
        row.setOnClickListener {
            it.haptic(HAPTIC_SELECTION)
            row.isOpening = true
            onOpenApp?.invoke(appUrl)
        }
        appRow = row
        val firstGap = if (payment.lines.isEmpty() && payment.details.isEmpty()) theme.dp(28) else theme.dp(4)
        add(row, before = firstGap)
        // (iOS: 20 under the row: the stack's 12 and 8 more on the next heading)
    }

    /** The app did not open (it is not installed), or the payer is back: the row is ready for another tap. */
    fun appOpenEnded() {
        appRow?.isOpening = false
    }

    private fun buildChoices() {
        val choices = mutableListOf<Choice>()
        if (pay.balance != null) choices.add(Choice.Balance)
        pay.cards.mapTo(choices) { Choice.Card(it) }
        // (under the "Pay with Raïs" row the headings sit 8 dp lower than the stack's own gap)
        val firstGap = if (appRow != null) theme.dp(20) else if (payment.lines.isEmpty() && payment.details.isEmpty()) theme.dp(28) else theme.dp(4)
        if (choices.isEmpty()) {
            add(heading(words[if (appRow == null) "pay.title" else "wallet.or_card"]), before = firstGap)
            return
        }
        choices.add(Choice.NewCard)

        if (pay.payer != null) {
            val row = Stack(context, vertical = false)
            row.add(heading(words["wallet.paying_as"]), width = 0, weight = 1f)
            val leave = SheetButton(context, theme, SheetButton.Kind.Plain, words["wallet.not_you"])
            leave.tag = "rais.signout"
            leave.onTap { onSignOut?.invoke() }
            row.add(leave, before = theme.dp(12))
            add(row, before = firstGap)
        } else {
            val title = heading(words["saved.title"])
            choicesHeading = title
            add(title, before = firstGap)
        }

        val box = GroupBox(context, theme)
        for (choice in choices) {
            val row = when (choice) {
                Choice.Balance -> {
                    val balance = pay.balance
                    val enough = balance?.enough ?: false
                    val icon = Glyph(context, R.drawable.rais_mark_rais, null)
                    icon.layoutParams = FrameLayout.LayoutParams(theme.dp(26), theme.dp(26), Gravity.CENTER)
                    ChoiceRow(context, theme, words["sheet.balance"], if (enough) balance?.text else (balance?.shortText ?: balance?.text), mark = icon).apply {
                        isEnabled = enough
                        tag = "rais.choice.balance"
                    }
                }
                is Choice.Card -> ChoiceRow(
                    context, theme, "•••• ${choice.card.last4}", "${words["pay.expiry"]} ${choice.card.expiry}", latinTitle = true,
                    mark = BrandMarks.single(context, theme, choice.card.brand, 30) ?: cardGlyph(R.drawable.rais_ic_card).first,
                ).apply { tag = "rais.choice.card" }
                Choice.NewCard -> {
                    val glyph = cardGlyph(R.drawable.rais_ic_add)
                    newCardGlyph = glyph.second
                    ChoiceRow(context, theme, newCardTitle, null, mark = glyph.first).apply {
                        tag = "rais.choice.new"
                        newCardRow = this
                    }
                }
            }
            row.setOnClickListener {
                it.haptic(HAPTIC_SELECTION)
                // while the saved ways to pay are folded away this row reads "Use a saved card": it brings them back
                select(tappedChoice(choice, savedFolded, firstSavedChoice()), animated = true)
            }
            choiceRows.add(choice to row)
            box.add(row)?.let { separators[choice.key] = it }
        }
        add(box, before = if (pay.payer != null) theme.dp(4) else theme.dp(12))

        val balance = pay.balance
        val topup = balance?.topupUrl
        if (balance != null && !balance.enough && topup != null) {
            val link = SheetButton(context, theme, SheetButton.Kind.Plain, words["wallet.top_up"], R.drawable.rais_ic_add_circle)
            link.onTap { onOpen?.invoke(topup) }
            add(link, before = 0, width = WRAP, gravity = Gravity.START)
        }

        savedCvcCell.edit.numberPad(secret = true)
        savedCvcCell.edit.autofill(View.AUTOFILL_HINT_CREDIT_CARD_SECURITY_CODE)
        savedCvcCell.edit.tag = "rais.saved.cvc"
        savedCvcCell.formatter = CardRules::formatCvc
        savedCvcCell.onChange = { savedCvcGroup.setError(null) }
        savedCvcGroup.onFocus = { onFocus?.invoke(savedCvcGroup) }
        savedCvcGroup.visibility = GONE
        add(savedCvcGroup)
    }

    /** The little tile of a way to pay without a network mark, and the glyph in it (to be swapped). */
    private fun cardGlyph(@DrawableRes icon: Int): Pair<View, Glyph> {
        val inner = Glyph(context, icon) { theme.secondary }
        val frame = object : FrameLayout(context), Themed {
            override fun applyTheme() {
                background = theme.rounded(theme.background, theme.dp(7f))
            }
        }
        frame.applyTheme()
        frame.addView(inner, FrameLayout.LayoutParams(theme.dp(17), theme.dp(17), Gravity.CENTER))
        frame.layoutParams = FrameLayout.LayoutParams(theme.dp(30), theme.dp(30), Gravity.CENTER)
        return frame to inner
    }

    private fun heading(text: String): Label = Label(context, theme, 15f, 600).apply {
        this.text = text
        accessibilityHeading()
    }

    private val newCardTitle: String get() = words[if (pay.payer != null) "wallet.other_card" else "saved.other"]

    private fun initialChoice(): Choice = initialChoiceOf(pay)

    private fun firstSavedChoice(): Choice = firstSavedOf(pay)

    /** Folds the saved ways to pay away (only "another card" stays, selected) or shows them all again. */
    private fun setSavedFolded(folded: Boolean) {
        savedFolded = folded
        for ((candidate, row) in choiceRows) {
            val keep = !folded || candidate == Choice.NewCard
            row.visibility = if (keep) VISIBLE else GONE
            // the first row showing has nothing above it
            separators[candidate.key]?.visibility = if (folded) GONE else VISIBLE
        }
        // the "another card" row turns into the way back to the saved ones
        newCardRow?.setTitle(if (folded) words[if (pay.balance?.enough == true) "sheet.pick_saved_or_balance" else "sheet.pick_saved"] else newCardTitle)
        newCardRow?.isDisclosure = folded
        newCardGlyph?.set(if (folded) R.drawable.rais_ic_card else R.drawable.rais_ic_add)
        choicesHeading?.text = words[if (folded) "pay.title" else "saved.title"]
    }

    private fun select(new: Choice, animated: Boolean) {
        choice = new
        for ((candidate, row) in choiceRows) row.choiceSelected = candidate == new
        if (animated) animateChanges()   // (one transition for everything this choice changes, the fold included)
        if (animated && choiceRows.isNotEmpty()) {
            val folded = foldedAfter(new, savedFolded, canFoldSaved(pay))
            if (folded != savedFolded) setSavedFolded(folded)
        }
        val needsCvc = (new as? Choice.Card)?.card?.needsCvc ?: false
        if (new is Choice.Card) savedCvcCell.edit.contentDescription = words.fill("wallet.card_cvc", "last4" to new.card.last4)
        val typing = new == Choice.NewCard
        savedCvcGroup.visibility = if (needsCvc) VISIBLE else GONE
        newCardSection.visibility = if (typing) VISIBLE else GONE
        saveRow?.visibility = if (typing) VISIBLE else GONE
        if (!typing) hideKeyboard()
        if (needsCvc && animated) post { savedCvcCell.focus() }
        onChoice?.invoke()
    }

    private fun hideKeyboard() {
        val focused = findFocus() ?: return
        focused.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
    }

    // ── A card typed now ──

    private fun buildCardForm() {
        panCell.edit.numberPad()
        panCell.edit.autofill(View.AUTOFILL_HINT_CREDIT_CARD_NUMBER)
        panCell.edit.tag = "rais.card.number"
        panCell.formatter = CardRules::formatPan
        expiryCell.edit.numberPad()
        expiryCell.edit.autofill(View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DATE)
        expiryCell.edit.tag = "rais.card.expiry"
        expiryCell.formatter = CardRules::formatExpiry
        cvcCell.edit.numberPad(secret = true)
        cvcCell.edit.autofill(View.AUTOFILL_HINT_CREDIT_CARD_SECURITY_CODE)
        cvcCell.edit.tag = "rais.card.cvc"
        cvcCell.formatter = CardRules::formatCvc
        nameCell.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        nameCell.edit.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        nameCell.edit.autofill(View.AUTOFILL_HINT_NAME)
        nameCell.edit.tag = "rais.card.name"
        nameCell.maxLength = 26

        panCell.onChange = {
            val number = CardRules.digits(panCell.text)
            brands.show(CardRules.brand(number))
            cardGroup.setError(null)
            if (number.length == CardRules.PAN_LENGTH && panCell.edit.isFocused && panCell.edit.selectionEnd == panCell.text.length) expiryCell.focus()
        }
        expiryCell.onChange = {
            cardGroup.setError(null)
            if (CardRules.digits(expiryCell.text).length == 4 && expiryCell.edit.isFocused) cvcCell.focus()
        }
        cvcCell.onChange = {
            cardGroup.setError(null)
            if (cvcCell.text.length == CardRules.CVC_LENGTH && cvcCell.edit.isFocused && nameCell.text.isEmpty()) nameCell.focus()
        }
        nameCell.onChange = { nameGroup.setError(null) }
        cardGroup.onFocus = {
            onCardEditing?.invoke()
            onFocus?.invoke(cardGroup)
        }
        nameGroup.onFocus = {
            onCardEditing?.invoke()
            onFocus?.invoke(nameGroup)
        }

        newCardSection.add(cardGroup)
        newCardSection.add(nameGroup)
        add(newCardSection, before = theme.dp(12))
    }

    // ── The receipt address, and whether the card is kept ──

    private fun buildAgreements() {
        if (pay.receiptEmail) {
            val cell = InputCell(context, theme, words["pay.email"], "name@example.com")
            cell.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            cell.edit.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            cell.edit.autofill(View.AUTOFILL_HINT_EMAIL_ADDRESS)
            cell.edit.tag = "rais.email"
            cell.maxLength = 254
            val group = InputGroup(context, theme, listOf(listOf(cell)), note = words["pay.email_note"])
            cell.onChange = { group.setError(null) }
            group.onFocus = { onFocus?.invoke(group) }
            add(group)
            emailCell = cell
            emailGroup = group
        }

        pay.save?.let { save ->
            val row = ToggleRow(context, theme, save.label, save.checked)
            row.tag = "rais.save"
            add(row)
            saveRow = row
        }
    }

    // ── Reading what was typed ──

    /**
     * What to pay with, once everything that is needed is there and right. Otherwise says what is wrong, at the fields, puts the payer in
     * the first of them, and returns `null`.
     */
    fun collect(): Submission? {
        var firstWrong: InputCell? = null
        fun wrong(cell: InputCell) {
            if (firstWrong == null) firstWrong = cell
        }
        var method: CheckoutApi.Method? = null

        when (val chosen = choice) {
            Choice.Balance -> method = CheckoutApi.Method.Balance
            is Choice.Card -> {
                if (chosen.card.needsCvc) {
                    val problem = CardRules.checkCvc(savedCvcCell.text)
                    if (problem != null) {
                        savedCvcGroup.setError(words[problem], listOf(savedCvcCell))
                        wrong(savedCvcCell)
                    } else {
                        method = CheckoutApi.Method.Card(chosen.card, savedCvcCell.text)
                    }
                } else {
                    method = CheckoutApi.Method.Card(chosen.card, null)
                }
            }
            Choice.NewCard -> {
                val problems = listOf(
                    panCell to CardRules.checkPan(panCell.text, sheetContext.sandbox),
                    expiryCell to CardRules.checkExpiry(expiryCell.text, sheetContext.sandbox),
                    cvcCell to CardRules.checkCvc(cvcCell.text),
                )
                val bad = problems.filter { it.second != null }
                cardGroup.setError(bad.firstOrNull()?.second?.let { words[it] }, bad.map { it.first })
                bad.firstOrNull()?.let { wrong(it.first) }
                val nameProblem = CardRules.checkName(nameCell.text)
                nameGroup.setError(nameProblem?.let { words[it] }, if (nameProblem == null) emptyList() else listOf(nameCell))
                if (nameProblem != null) wrong(nameCell)
                if (bad.isEmpty() && nameProblem == null) {
                    val expiry = CardRules.digits(expiryCell.text)
                    val card = TypedCard(
                        pan = CardRules.digits(panCell.text), cvc = cvcCell.text, expiryMonth = expiry.take(2),
                        expiryYear = "20" + expiry.takeLast(2), holder = CardRules.cleanName(nameCell.text).uppercase(),
                    )
                    method = CheckoutApi.Method.NewCard(card, saveRow?.isOn ?: false)
                }
            }
        }

        var email: String? = null
        val cell = emailCell
        val group = emailGroup
        if (cell != null && group != null) {
            val typed = cell.text.trim()
            val problem = CardRules.checkEmail(typed)
            if (problem != null) {
                group.setError(words[problem], listOf(cell))
                wrong(cell)
            } else {
                email = typed.ifEmpty { null }
            }
        }

        firstWrong?.let {
            it.focus()
            it.haptic(HAPTIC_ERROR)
            return null
        }
        return method?.let { Submission(it, email) }
    }

    /** The server refused the receipt address: said at its field, like the screen's own checks. */
    fun showEmailProblem(text: String): Boolean {
        val cell = emailCell ?: return false
        emailGroup?.setError(text, listOf(cell))
        cell.focus()
        return true
    }

    /** The server refused the security code of a saved card. */
    fun showSavedCvcProblem(text: String): Boolean {
        val card = (choice as? Choice.Card)?.card ?: return false
        if (!card.needsCvc) return false
        savedCvcGroup.setError(text, listOf(savedCvcCell))
        savedCvcCell.focus()
        return true
    }

    /** A card whose code the server turned out to need. */
    fun askForSavedCvc(): Boolean {
        if (choice !is Choice.Card) return false
        savedCvcGroup.visibility = VISIBLE
        savedCvcGroup.setError(words["js.code.cvc_required"], listOf(savedCvcCell))
        savedCvcCell.focus()
        return true
    }

    val buttonTitle: String get() = words[if (choice == Choice.Balance) "wallet.pay_balance" else "pay.cta"]

    /** A payment is on its way: nothing can be changed until it answers. */
    var isBusy = false
        set(value) {
            field = value
            setDescendantsEnabled(this, !value)
            if (value) hideKeyboard()
        }

    private fun setDescendantsEnabled(view: View, enabled: Boolean) {
        if (view is EditText) view.isEnabled = enabled
        if (view is ChoiceRow) view.isClickable = enabled
        if (view is RaisPayRow) view.isEnabled = enabled
        if (view is ViewGroup) for (index in 0 until view.childCount) setDescendantsEnabled(view.getChildAt(index), enabled)
    }
}

/** The ways to pay and what choosing one does to them, as plain functions (the views only draw what these say). */
internal fun initialChoiceOf(pay: Sheet.Pay): PayView.Choice {
    pay.chosen?.let { chosen -> pay.cards.firstOrNull { it.id == chosen }?.let { return PayView.Choice.Card(it) } }
    if (pay.balance?.enough == true) return PayView.Choice.Balance
    pay.cards.firstOrNull()?.let { return PayView.Choice.Card(it) }
    return PayView.Choice.NewCard
}

/** Saved ways to pay can be folded away only when one of them could be paid with. */
internal fun canFoldSaved(pay: Sheet.Pay): Boolean = pay.cards.isNotEmpty() || pay.balance?.enough == true

/** The saved way to pay that the "Use a saved card" row picks: the one the screen would start with, else the first saved card. */
internal fun firstSavedOf(pay: Sheet.Pay): PayView.Choice {
    val first = initialChoiceOf(pay)
    return if (first == PayView.Choice.NewCard) pay.cards.firstOrNull()?.let { PayView.Choice.Card(it) } ?: PayView.Choice.NewCard else first
}

/** What a tap on [tapped] selects: while the saved ways to pay are folded away, "another card" is the way back to them. */
internal fun tappedChoice(tapped: PayView.Choice, folded: Boolean, firstSaved: PayView.Choice): PayView.Choice =
    if (tapped == PayView.Choice.NewCard && folded) firstSaved else tapped

/**
 * Whether the saved ways to pay are folded after [selected] was chosen: choosing "another card" folds them (so the whole card form fits on
 * screen) when there is something to fold, and choosing a saved one brings them back.
 */
internal fun foldedAfter(selected: PayView.Choice, folded: Boolean, canFold: Boolean): Boolean = when {
    selected == PayView.Choice.NewCard && !folded && canFold -> true
    selected != PayView.Choice.NewCard && folded -> false
    else -> folded
}

/** Marks a label as a heading for TalkBack. */
internal fun View.accessibilityHeading() {
    if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
}
