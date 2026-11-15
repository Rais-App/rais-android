# Changelog

All notable changes to the Raïs Android SDK. The SDK follows [semantic versioning](https://semver.org). The checkout page itself is hosted by Raïs and improves without an SDK release; changes to what the page sends the app are announced here first.

## Unreleased

- **Pay with Raïs**, first on the screen when the store can be paid from a Raïs wallet: with the Raïs app (`app.rais.wallet`: it opens on the payment, the payer confirms and the app brings them back to your app through the payment's `success_url`), or, without the app, by signing in with Raïs on the Raïs website in a Custom Tab and then paying from the balance or a saved card in the SDK's own screen. No configuration. The SDK's manifest no longer carries a `<queries>` entry (the app is opened by an explicit intent); it adds one small exported activity that receives the website's answer on the scheme `raiscb-androidsdk` (override the string `rais_callback_scheme` to give an app a scheme of its own).
- Choosing "Use another card" folds the saved ways to pay (balance, saved cards) away so the whole card form fits on screen; the "Use another card" row itself then reads "Use a saved card" and brings them back, and picking one of them hides the card form again.
- Back from the Raïs app, a payment that was just made reads "Payment Successful" (not "Invoice Already Paid"): the screen reads the payment again, fresh, when it comes back to the front.
- No call goes out after the checkout has ended: the network client refuses calls once the checkout is closed, and the screen's `onResume`, its poller and its callbacks do nothing for an ended checkout (iOS crashed on exactly this when the payer came back from the Raïs app).
- Keyboard: a tap on a button with the keyboard open fires on the first tap. (The Android screen already acted on a finished tap only; this is now pinned by a test, with every touch handler audited.)
- `additionalTrustedHosts` and plain http for a private-network host: unchanged, as on iOS (only when the app lists that host itself); the README now shows the `network_security_config` an app needs for it.
- Server (additive, the same contract as the iOS SDK): `GET /m/v1/payments/{id}` carries `return_urls`, `POST /m/v1/payments/{id}/pay` carries `return_url`; `GET /pay/{id}/raisid/start?native=raiscb-…` hands the payer session to the app.

## 1.0.0 (2026-11-14)

First release.

- The SDK's own words (close, "Can't load the payment page", Try again) follow `Configuration.language`, not only the phone's language.
- Built for Kotlin 2.0 hosts (API and language level 2.0, standard library 2.0.21): apps on Kotlin 2.0 to 2.3, React Native and Expo apps among them, can use it; a build for 2.4 could not be read by them.
- The checkout is drawn with native views (`Configuration.style = Style.Native`, the default), the same screen as the iOS SDK: the payment and its details, card fields for CIB and Edahabia (formatted as typed, the network's mark following the number), a store's saved cards, the payer's Raïs balance and cards, "save this card" as a switch, the receipt address when the payment asks for it, the bank's code in six boxes, and the result with its PDF receipt (viewed and shared in the checkout) and the receipt by e-mail. It reads `GET /pay/{id}/sheet` and makes the hosted page's own calls with an in-memory cookie jar; the robot check's proof of work is solved in the background.
- `Configuration.style = Style.Web` shows the hosted page in a WebView, as in 1.0. The native screen falls back to it by itself, keeping the payer's session, for a payment whose card is typed on the bank's own page and for a server without a native checkout.
- What scrolls under the status bar, the close button and the test badge fades into the page (the fade appears only once the page has scrolled); the badge is opaque and the close button sits on its own disc.
- Keyboard: the pay button floats in a pane that rides on the keyboard, and the field typed in is always fully above the keyboard and the pane: the last field on the page, the bank's code and the receipt e-mail form (its note and its Send button) included.
- Arabic reads right to left in the checkout even when the app does not declare `android:supportsRtl` (only the checkout's own views are affected).
- `Appearance.fontProvider`: the native checkout in the app's own typeface. `Configuration.merchantLogo`: a drawable shown instead of the logo of the merchant's Raïs account, and `Configuration.merchantLogoUrl`: the same as an https address (for React Native and Flutter bridges; other schemes are ignored, `merchantLogo` wins). `createIntent` hands `fontProvider` to the screen like `present` does. `RaisCheckout.dismiss()`: closes the checkout from code.
- A development server on the local network works on a real phone: `additionalTrustedHosts` now accepts a private address (192.168.x.x, 10.x, 172.16-31.x, 100.64-127.x, `*.local`, `*.ts.net`) over plain http. Public hosts still need https.
- The example app opens a checkout from intent extras (`url`, `lang`, `scheme`, `style`, `font`, `present`, `autoclose`…) for automated runs.

- `RaisCheckout`: shows the hosted checkout in its own screen and returns `Completed`, `Cancelled` or `Failed`. Works from an activity or a fragment, registered like any activity-result API.
- Light and dark mode, following the device live or fixed with `colorScheme`.
- `Appearance`: separate light and dark `Colors`, corner radius, font family. `Configuration`: language, close button, return URL scheme, additional trusted hosts.
- The keyboard is owned by the native screen: the WebView is placed above it with window insets, so the page never jumps. Works edge to edge on every Android version, including targetSdk 35.
- The checkout address is validated (https on `rais.app`, or loopback and `10.0.2.2` for testing); the page's bridge to the app is restricted to the checkout's own origin (`WebMessageListener`, WebView 86+).
- `createIntent` and `parseResult` for framework integrations (React Native).
