# সমস্যা সমাধান / Troubleshooting

## Termux কমান্ড চালাতে এরর (Termux command errors)
NISU AI-কে বলুন "termux status চেক করো" — সে সব ধাপ পরীক্ষা করে ঠিক কী ঠিক করতে হবে বলে দেবে।

1. Termux অবশ্যই **F-Droid বা GitHub** থেকে ইনস্টল করুন (Play Store সংস্করণ পুরনো, কাজ করে না)।
2. **Termux ইনস্টলের পরে** NISU AI ইনস্টল করুন। উল্টো হলে NISU AI একবার আনইনস্টল করে আবার ইনস্টল করুন।
3. Termux-এ একবার চালান:
   `mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties`
   তারপর Termux পুরোপুরি বন্ধ করে আবার খুলুন।
4. NISU AI খুলে "Grant permissions" চাপুন এবং Termux-এর "Run commands in Termux environment" অনুমতি দিন
   (অথবা Settings > Apps > NISU AI > Permissions > Additional permissions)। Root থাকলে বলুন "নিজেকে সেটআপ করো"।
5. Termux-এর ব্যাটারি অপ্টিমাইজেশন বন্ধ করুন।

## APK বিল্ড এরর (Termux-এ)
- প্রজেক্ট /sdcard-এ থাকলেও এখন স্ক্রিপ্ট নিজে Termux-এর হোমে কপি করে বিল্ড করে।
- "Killed" / মেমরি এরর: `NISU_HEAP=1536m bash build-termux.sh`
- Root ফোনে বিল্ড মাঝপথে মারা গেলে: `NISU_FIX_PHANTOM=1 bash build-termux.sh`
- ফেইল করলে লগ `/sdcard/Download/nisu-build.log` ফাইলে সেভ হয়; `e:` দিয়ে শুরু হওয়া লাইনগুলো পাঠান।

## WhatsApp / Messenger
- "Message access is OFF": allow Notification access for NISU AI (see CHANGES.md).
- Send result says `unknown`: the Send button was pressed but the box did not clear. Say "read the chat" before retrying so it is not sent twice.
- Wrong chat opened: the result includes `chat_title` and a `warning` when it does not match the name; NISU AI should confirm with you first.
- Number not recognised: set your country code in Settings.

## NISU AI connect হচ্ছে না / Connection problems
- Status লাইনে কারণ দেখায়: "API key rejected" → Settings-এ কী ঠিক করুন; "Model not accepted" → Live model name-এ `gemini-3.8-live` দিন (পুরনো নাম নিজে থেকেই বদলে যায়); "quota used up" → পরে চেষ্টা করুন বা অন্য কী দিন।
- ইন্টারনেট না থাকলে "Waiting for internet…" দেখাবে, নেট এলে নিজে থেকে কানেক্ট হবে।
- প্রতি ~১০ মিনিটে সার্ভার কানেকশন বন্ধ করে; এখন NISU AI সাথে সাথে আগের কথোপকথন ধরে আবার কানেক্ট হয়।
- ফোনের Autostart / "Unrestricted battery" চালু না থাকলে ব্যাকগ্রাউন্ডে কানেকশন মারা যেতে পারে।

## অ্যাপ বিল্ডার / App builder
- "Termux is not ready": ওপরের Termux ধাপগুলো করুন (F-Droid Termux, allow-external-apps, Run-command permission)।
- প্রথম বিল্ডে টুল ইনস্টল হয় (১০-৩০ মিনিট, ~১ GB)। Termux-এর ব্যাটারি অপ্টিমাইজেশন বন্ধ রাখুন, নইলে Android ব্যাকগ্রাউন্ড বিল্ড মেরে দিতে পারে (root ফোনে `NISU_FIX_PHANTOM` দেখুন)।
- বিল্ড ফেইল করলে NISU AI নিজে ৩ বার ঠিক করার চেষ্টা করে; তারপরও না হলে কারণ বলে দেয়। লগ: Termux-এ `~/nisu-apps/NAME/build.log`।
- সোর্স কোড থাকে Termux-এ `~/nisu-apps/NAME`; APK: `/sdcard/Download/NISU-AI-NAME.apk`।
- Install ব্লক হলে: Android Settings > Apps > Termux > Install unknown apps চালু করুন, অথবা ফাইল ম্যানেজার থেকে APK খুলুন।
