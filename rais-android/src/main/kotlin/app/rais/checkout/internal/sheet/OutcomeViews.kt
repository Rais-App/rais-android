package app.rais.checkout.internal.sheet

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ProgressBar
import app.rais.checkout.R

/** The bank's step: the code it sent to the cardholder. */
@SuppressLint("ViewConstructor")
internal class CodeView(context: Context, sheetContext: SheetContext, maskedCard: String?, length: Int) :
    Stack(context, vertical = true, gap = sheetContext.theme.dp(12)) {
    private val theme = sheetContext.theme
    val code = CodeField(context, theme, length, sheetContext.words["otp.label"])
    private val problem = Label(context, theme, 13f) { theme.danger }.centered()

    init {
        val words = sheetContext.words
        setPadding(0, theme.dp(12), 0, 0)
        code.tag = "rais.code"

        val head = Stack(context, vertical = true)
        head.gravity = Gravity.CENTER_HORIZONTAL
        head.add(IconDisc(context, theme, R.drawable.rais_ic_shield, { theme.text }), width = theme.dp(64), height = theme.dp(64))
        val title = Label(context, theme, 22f, 700).centered()
        title.text = words["otp.title"]
        title.accessibilityHeading()
        head.add(title, before = theme.dp(16))
        val lead = Label(context, theme, 15f) { theme.secondary }.centered()
        lead.text = words["otp.subtitle"]
        head.add(lead, before = theme.dp(8))
        val masked = CardRules.formatMasked(maskedCard)
        if (masked.isNotEmpty()) {
            val card = Label(context, theme, 15f, 500).centered()
            theme.mono(card, 15f, 500)
            card.text = masked
            card.textDirection = TEXT_DIRECTION_LTR
            head.add(card, before = theme.dp(12))
        }
        add(head)

        add(code, before = theme.dp(28))
        problem.visibility = GONE
        add(problem)

        val warn = Stack(context, vertical = false)
        warn.gravity = Gravity.CENTER
        warn.add(Glyph(context, R.drawable.rais_ic_warning) { theme.warning }, width = theme.dp(14), height = theme.dp(14))
        val warning = Label(context, theme, 13f) { theme.secondary }
        warning.text = words["otp.warning"]
        warn.add(warning, before = theme.dp(6))
        add(warn, before = theme.dp(20), width = WRAP, gravity = Gravity.CENTER_HORIZONTAL)
    }

    /** Says what is wrong with the code under its cells; `null` clears it. */
    fun setProblem(text: String?) {
        problem.text = text
        problem.visibility = if (text == null) GONE else VISIBLE
        code.hasError = text != null
        if (text != null) {
            code.shake()
            announceForAccessibility(text)
        }
    }
}

/**
 * What became of a payment: paid, refused, expired, cancelled, or still being confirmed with the bank. The server words all of it; this
 * draws it, and offers what can be done next (the receipt, another try).
 */
@SuppressLint("ViewConstructor")
internal class OutcomeView(context: Context, private val sheetContext: SheetContext, sheet: Sheet, result: Sheet.Outcome) :
    Stack(context, vertical = true, gap = sheetContext.theme.dp(12)) {
    private val theme get() = sheetContext.theme
    private val words get() = sheetContext.words
    private var emailButton: SheetButton? = null
    private var emailCell: InputCell? = null
    private var emailGroup: InputGroup? = null
    private var sendButton: SheetButton? = null
    private var emailForm: Stack? = null
    private var receiptButton: SheetButton? = null
    private var progressRow: View? = null

    var onViewReceipt: (() -> Unit)? = null
    /** E-mail the receipt, to the address typed here when the payment has none. */
    var onEmailReceipt: ((String?) -> Unit)? = null
    var onContact: ((String) -> Unit)? = null
    var onFocus: ((View) -> Unit)? = null

    init {
        setPadding(0, theme.dp(12), 0, 0)
        val color: () -> Int = when (result.tone) {
            Sheet.Outcome.Tone.Ok -> ({ theme.success })
            Sheet.Outcome.Tone.Bad -> ({ theme.danger })
            Sheet.Outcome.Tone.Neutral -> ({ theme.secondary })
        }
        val head = Stack(context, vertical = true)
        head.gravity = Gravity.CENTER_HORIZONTAL
        head.add(IconDisc(context, theme, outcomeIcon(result.icon), color), width = theme.dp(64), height = theme.dp(64))
        val title = Label(context, theme, 22f, 700).centered()
        title.text = result.title
        title.tag = "rais.outcome.title"
        title.accessibilityHeading()
        head.add(title, before = theme.dp(16))
        result.lead?.takeIf { it.isNotEmpty() }?.let {
            val lead = Label(context, theme, 15f) { theme.secondary }.centered()
            lead.text = it
            head.add(lead, before = theme.dp(6))
        }
        if (result.confirming != null) {
            val row = Stack(context, vertical = false)
            row.gravity = Gravity.CENTER
            val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall)
            spinner.indeterminateTintList = ColorStateList.valueOf(theme.secondary)
            row.add(spinner, width = theme.dp(18), height = theme.dp(18))
            val label = Label(context, theme, 14f, 500) { theme.secondary }
            label.text = words["confirming.checking"]
            row.add(label, before = theme.dp(8))
            head.add(row, before = theme.dp(16), width = WRAP, gravity = Gravity.CENTER_HORIZONTAL)
            progressRow = row
        }
        sheet.payment?.let { payment ->
            head.add(AmountLabel(context, theme, payment.amountNumber, payment.currency, 34f, payment.amountText), before = theme.dp(18))
            sheet.merchant?.name?.let { name ->
                val merchant = Label(context, theme, 15f, 500) { theme.secondary }.centered()
                merchant.text = name
                head.add(merchant, before = theme.dp(2))
            }
        }
        add(head)

        var nextGap = theme.dp(24)
        result.note?.takeIf { it.isNotEmpty() }?.let { note ->
            add(NoticeView(context, theme, NoticeView.Tone.Neutral, note, if (result.receipt != null) R.drawable.rais_ic_mail else null), before = nextGap)
            nextGap = theme.dp(12)
        }
        if (result.rows.isNotEmpty()) {
            add(rowsCard(sheetContext, context, result.rows), before = nextGap)
            nextGap = theme.dp(12)
        }

        result.receipt?.let { receipt ->
            val view = SheetButton(context, theme, SheetButton.Kind.Secondary, words["sheet.view_receipt"], R.drawable.rais_ic_receipt)
            view.tag = "rais.receipt"
            view.onTap { onViewReceipt?.invoke() }
            receiptButton = view
            val row = Stack(context, vertical = false)
            row.add(view, width = 0, weight = 1f)
            val mail = receipt.mail
            if (mail != null) {
                val send = SheetButton(
                    context, theme, SheetButton.Kind.Secondary, words[if (mail.sent) "result.receipt_emailed" else "result.email_receipt"],
                    if (mail.sent) R.drawable.rais_ic_check else R.drawable.rais_ic_mail_outline,
                )
                send.tag = "rais.receipt.email"
                send.isEnabled = !mail.sent
                send.onTap { emailTapped(known = mail.known != null) }
                emailButton = send
                row.add(send, before = theme.dp(10), width = 0, weight = 1f)
            }
            add(row, before = if (nextGap == theme.dp(24)) nextGap else theme.dp(16))
            nextGap = theme.dp(12)

            if (mail != null && !mail.sent && mail.known == null) {
                val cell = InputCell(context, theme, words["result.email_label"], "name@example.com")
                cell.edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                cell.edit.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                cell.edit.autofill(View.AUTOFILL_HINT_EMAIL_ADDRESS)
                cell.edit.tag = "rais.receipt.address"
                cell.maxLength = 254
                val group = InputGroup(context, theme, listOf(listOf(cell)), note = words["result.email_hint"])
                val sendTyped = SheetButton(context, theme, SheetButton.Kind.Secondary, words["result.email_send"], R.drawable.rais_ic_send)
                sendTyped.tag = "rais.receipt.send"
                sendTyped.onTap { sendTyped() }
                cell.onChange = { group.setError(null) }
                cell.onReturn = { sendTyped() }
                val form = Stack(context, vertical = true, gap = theme.dp(10))
                form.add(group)
                form.add(sendTyped)
                // the whole form comes into view above the keyboard: the field, what is said under it, and its Send button
                group.onFocus = { onFocus?.invoke(form) }
                form.visibility = GONE
                add(form)
                emailCell = cell
                emailGroup = group
                sendButton = sendTyped
                emailForm = form
            }
        }

        result.contactEmail?.takeIf { it.isNotEmpty() }?.let { address ->
            val contact = SheetButton(context, theme, SheetButton.Kind.Secondary, words["expired.contact"], R.drawable.rais_ic_mail_outline)
            contact.onTap { onContact?.invoke(address) }
            add(contact, before = nextGap)
            nextGap = theme.dp(12)
        }

        result.support?.takeIf { it.isNotEmpty() }?.let { support ->
            val label = Label(context, theme, 12f) { theme.secondary }.centered()
            label.text = support
            add(label, before = theme.dp(20))
        }
    }

    /** The bank still names no outcome and the screen stopped asking by itself: the payer can ask again. */
    fun stopProgress() {
        progressRow?.visibility = GONE
    }

    // ── The receipt ──

    var isOpeningReceipt = false
        set(value) {
            field = value
            receiptButton?.isLoading = value
        }

    private fun emailTapped(known: Boolean) {
        val form = emailForm
        if (form == null || known) {
            emailButton?.isLoading = true
            onEmailReceipt?.invoke(null)
            return
        }
        val show = form.visibility != VISIBLE
        (rootView as? ViewGroup)?.let { TransitionManager.beginDelayedTransition(it, AutoTransition().setDuration(220)) }
        form.visibility = if (show) VISIBLE else GONE
        if (show) post { emailCell?.focus() } else emailCell?.blur()
    }

    private fun sendTyped() {
        val cell = emailCell ?: return
        val group = emailGroup ?: return
        val address = cell.text.trim()
        if (address.isEmpty()) {
            group.setError(words["js.receipt_email_required"], listOf(cell))
            return
        }
        if (CardRules.checkEmail(address) != null) {
            group.setError(words["js.receipt_email_invalid"], listOf(cell))
            return
        }
        sendButton?.isLoading = true
        cell.blur()
        onEmailReceipt?.invoke(address)
    }

    /** The receipt went (or had already gone) to [address], masked by the server. */
    fun receiptEmailed(address: String) {
        emailButton?.isLoading = false
        sendButton?.isLoading = false
        emailButton?.title = words["result.receipt_emailed"]
        emailButton?.icon = R.drawable.rais_ic_check
        emailButton?.isEnabled = false
        emailForm?.takeIf { it.visibility == VISIBLE }?.let {
            (rootView as? ViewGroup)?.let { root -> TransitionManager.beginDelayedTransition(root, AutoTransition().setDuration(220)) }
            it.visibility = GONE
        }
        haptic(HAPTIC_SUCCESS)
        announceForAccessibility(words.fill("js.receipt_sent", "email" to address))
    }

    /** The receipt could not be e-mailed. Returns whether the problem was shown at the address field (else the screen says it). */
    fun receiptEmailFailed(text: String): Boolean {
        emailButton?.isLoading = false
        sendButton?.isLoading = false
        val cell = emailCell ?: return false
        val form = emailForm ?: return false
        if (form.visibility != VISIBLE) return false
        emailGroup?.setError(text, listOf(cell))
        return true
    }
}
