package ai.nisu.assistant

import java.net.URLEncoder

/*
 * v3.97: what NISU AI KNOWS about the apps people use every day (Google apps, chat, music, video, APK / app stores, AI apps,
 * payment and daily-life apps). It is data plus three small pure rules, nothing here touches the screen:
 *  - resolve(name): "yt", "chat gpt", a Bengali name, "com.whatsapp" -> the app entry (aliases are matched ignoring case, spaces and punctuation).
 *  - link(app, action, text): the app's own deep link for search / play / navigate / chat / user / details, so one call opens the right page
 *    without tapping through menus (PhoneControl.appDo starts it and checks that the app really came to the front).
 *  - guide(app): the short playbook (screen labels, steps, pitfalls) that is returned by open_app (as "tips") and by app_do action guide.
 * Package names are HINTS: an app is found by package first (exact, fast) and by its label afterwards, so a wrong or renamed package
 * never stops an app from opening. UI labels change between versions and languages: the screen returned by every action is the truth.
 */
object AppKnowledge {
    class App(
        val key: String,
        val label: String,
        val group: String,
        val aliases: List<String>,
        val packages: List<String>,
        /** action -> link template; {q} is replaced by the url-encoded text. */
        val links: Map<String, String>,
        val how: String
    )

    /** Same normalisation as PhoneControl.appKey: lower case, no spaces and no punctuation (Bengali vowel signs stay). */
    fun norm(s: String): String = s.lowercase().filterNot { it.isWhitespace() || it in "-_.'\u2019()\u00B7" }

    val GENERAL: String = "Popups first: click \"Not now\", \"Skip\", \"Maybe later\", \"No thanks\", \"Close\" or \"Dismiss\"; permission dialogs: \"While using the app\" or \"Allow\". Wait for loading to end before the next tap. Click by the exact label on the returned screen; if screen_changed is false, do not claim success."

    private val GROUP_HOW: Map<String, String> = mapOf("google" to "Google app: use the labels in the returned screen; popups (\"Not now\", \"Skip\", \"No thanks\", \"Allow\") are cleared first.", "social" to "Social / chat app: reading is free, but never send or post text he did not dictate or approve.", "music" to "Music app: search the song, click the right result, then confirm the player shows pause/play. If a login or paywall screen shows, say so.", "video" to "Video app: search the title, click the right result, confirm the player is on screen.", "store" to "App store: never install or download without his yes; check the app name and developer on the page before pressing Install.", "apk" to "APK website: open in the browser, find the exact app and version, ask his yes, then download. Only from the official site or a well-known mirror.", "ai" to "AI chat app: open, click the message box, type_text submit=true, wait 3-5 s, get_screen, read the full answer aloud as his answer. Login / limit screens must be reported, not guessed.", "pay" to "Money app: never type or speak a PIN; never confirm any payment without his exact yes.", "daily" to "Daily-life app: stop before any purchase, booking or order and ask his yes first.", "system" to "System app: labels differ by phone maker; read the returned screen before tapping.", "tools" to "Tool app: read the returned screen before tapping; root or permission dialogs need his yes.")

    val APPS: List<App> = listOf(
        App("google", "Google", "google", listOf("google app", "google search", "\u0997\u09C1\u0997\u09B2", "\u0997\u09C1\u0997\u09B2 \u0985\u09CD\u09AF\u09BE\u09AA"), listOf("com.google.android.googlequicksearchbox"), mapOf("search" to "https://www.google.com/search?q={q}"), "Search box on top; mic icon = voice search. For a web answer prefer action search (opens results at once)."),
        App("chrome", "Chrome", "google", listOf("google chrome", "browser", "\u0995\u09CD\u09B0\u09CB\u09AE", "\u09AC\u09CD\u09B0\u09BE\u0989\u099C\u09BE\u09B0"), listOf("com.android.chrome"), mapOf("search" to "https://www.google.com/search?q={q}"), "Address bar on top: tap it, type a word or a link, Enter. Tabs button shows the count. A cookie or notification popup: click \"Accept\", \"No thanks\" or \"Not now\" first."),
        App("gmail", "Gmail", "google", listOf("mail", "email", "google mail", "\u099C\u09BF\u09AE\u09C7\u0987\u09B2", "\u0987\u09AE\u09C7\u0987\u09B2"), listOf("com.google.android.gm"), emptyMap(), "Compose = the \"Compose\" button (bottom right). Fields: \"To\", \"Subject\", then the body; \"Send\" = paper-plane at the top. Search = magnifier on top. Never send before his yes to the exact text."),
        App("maps", "Google Maps", "google", listOf("google maps", "map", "maps", "\u09AE\u09CD\u09AF\u09BE\u09AA", "\u09AE\u09CD\u09AF\u09BE\u09AA\u09B8", "\u0997\u09C1\u0997\u09B2 \u09AE\u09CD\u09AF\u09BE\u09AA"), listOf("com.google.android.apps.maps"), mapOf("search" to "geo:0,0?q={q}", "navigate" to "google.navigation:q={q}", "details" to "https://www.google.com/maps/search/?api=1&query={q}"), "action navigate starts directions to a place; if it only shows the route click \"Start\". Transport modes are tabs on the route screen (drive, motorbike, transit, walk). Needs GPS on."),
        App("youtube", "YouTube", "video", listOf("yt", "you tube", "\u0987\u0989\u099F\u09BF\u0989\u09AC", "\u0987\u0989\u099F\u09BF\u0989\u09AD"), listOf("com.google.android.youtube"), emptyMap(), "Use the dedicated youtube tool for EVERY YouTube job (search, play, like, subscribe): it is verified and faster than app_do. Labels if you must use the screen: tabs Home, Shorts, Subscriptions, You; \"Skip\" / \"Skip ads\" on ads; Like, Dislike, Share, Save, Subscribe under the video."),
        App("ytmusic", "YouTube Music", "music", listOf("youtube music", "yt music", "\u0987\u0989\u099F\u09BF\u0989\u09AC \u09AE\u09BF\u0989\u099C\u09BF\u0995"), listOf("com.google.android.apps.youtube.music"), mapOf("search" to "https://music.youtube.com/search?q={q}", "play" to "https://music.youtube.com/search?q={q}"), "After play, click the first \"Song\" result (not a video or playlist unless he asked). Bottom player bar shows pause/play and next. Tabs: Home, Samples, Explore, Library."),
        App("photos", "Google Photos", "google", listOf("photos", "google photos", "\u0997\u09C1\u0997\u09B2 \u09AB\u099F\u09CB", "\u09AB\u099F\u09CB\u099C"), listOf("com.google.android.apps.photos"), emptyMap(), "Tabs: Photos, Collections (Search). Select = long-press a photo; Share and Delete icons appear. Delete moves to Trash for 60 days; ask him first."),
        App("drive", "Google Drive", "google", listOf("drive", "google drive", "\u0997\u09C1\u0997\u09B2 \u09A1\u09CD\u09B0\u09BE\u0987\u09AD", "\u09A1\u09CD\u09B0\u09BE\u0987\u09AD"), listOf("com.google.android.apps.docs"), emptyMap(), "Tabs: Home, Starred, Shared, Files. \"+\" (bottom right) = upload / new folder / scan. Three dots on a file = share, download, rename."),
        App("docs", "Google Docs", "google", listOf("docs", "google docs", "\u0997\u09C1\u0997\u09B2 \u09A1\u0995\u09CD\u09B8"), listOf("com.google.android.apps.docs.editors.docs"), emptyMap(), "Tap a document to open; pencil icon = edit mode. \"+\" makes a new document."),
        App("sheets", "Google Sheets", "google", listOf("sheets", "google sheets", "\u0997\u09C1\u0997\u09B2 \u09B6\u09C0\u099F\u09B8"), listOf("com.google.android.apps.docs.editors.sheets"), emptyMap(), "Tap a cell to select it; the formula bar is on top; \"+\" makes a new sheet."),
        App("slides", "Google Slides", "google", listOf("slides", "google slides"), listOf("com.google.android.apps.docs.editors.slides"), emptyMap(), "Tap a slide to edit; \"Present\" plays the slideshow."),
        App("calendar", "Google Calendar", "google", listOf("calendar", "google calendar", "\u0995\u09CD\u09AF\u09BE\u09B2\u09C7\u09A8\u09CD\u09A1\u09BE\u09B0", "\u0997\u09C1\u0997\u09B2 \u0995\u09CD\u09AF\u09BE\u09B2\u09C7\u09A8\u09CD\u09A1\u09BE\u09B0"), listOf("com.google.android.calendar"), emptyMap(), "New event = \"+\" (bottom right) > Event: title, date, time, \"Save\". For a simple alarm or reminder use set_reminder instead."),
        App("meet", "Google Meet", "google", listOf("meet", "google meet", "\u0997\u09C1\u0997\u09B2 \u09AE\u09BF\u099F", "\u09AE\u09BF\u099F"), listOf("com.google.android.apps.tachyon"), emptyMap(), "\"New meeting\" or \"Join with a code\"; allow camera and microphone when asked (\"While using the app\")."),
        App("gmessages", "Google Messages", "google", listOf("messages", "sms", "google messages", "text messages", "\u09AE\u09C7\u09B8\u09C7\u099C", "\u098F\u09B8\u098F\u09AE\u098F\u09B8"), listOf("com.google.android.apps.messaging", "com.android.messaging", "com.android.mms"), emptyMap(), "For an SMS use send_sms. In the app: \"Start chat\" (bottom right), type the number or name, then the message, then \"Send SMS\"."),
        App("phone", "Phone", "system", listOf("dialer", "phone app", "call app", "\u09AB\u09CB\u09A8", "\u09A1\u09BE\u09AF\u09BC\u09BE\u09B2\u09BE\u09B0"), listOf("com.google.android.dialer", "com.android.dialer"), emptyMap(), "To call use call_number (checks the call really started). Recents tab = call history."),
        App("contacts", "Contacts", "system", listOf("contact", "contacts", "\u0995\u09A8\u09CD\u099F\u09BE\u0995\u09CD\u099F", "\u0995\u09A8\u09CD\u099F\u09BE\u0995\u09CD\u099F\u09B8"), listOf("com.google.android.contacts", "com.android.contacts"), emptyMap(), "To find a number use find_contact first; it is faster than the app."),
        App("clock", "Clock", "system", listOf("alarm", "timer", "stopwatch", "clock", "\u0998\u09A1\u09BC\u09BF", "\u0985\u09CD\u09AF\u09BE\u09B2\u09BE\u09B0\u09CD\u09AE"), listOf("com.google.android.deskclock", "com.android.deskclock"), emptyMap(), "Tabs: Alarm, Clock, Timer, Stopwatch. For a plain alarm or reminder use set_reminder."),
        App("keep", "Google Keep", "google", listOf("keep", "google keep", "notes", "\u0997\u09C1\u0997\u09B2 \u0995\u09BF\u09AA"), listOf("com.google.android.keep"), emptyMap(), "\"Take a note\" box at the bottom; title first, then the text; back saves it automatically."),
        App("translate", "Google Translate", "google", listOf("translate", "google translate", "\u099F\u09CD\u09B0\u09BE\u09A8\u09CD\u09B8\u09B2\u09C7\u099F", "\u0985\u09A8\u09C1\u09AC\u09BE\u09A6"), listOf("com.google.android.apps.translate"), mapOf("search" to "https://translate.google.com/?sl=auto&tl=en&op=translate&text={q}"), "Two language buttons on top (from / to); tap the box and type. The shortcut translates into English; for another language change the right-hand language button."),
        App("lens", "Google Lens", "google", listOf("lens", "google lens"), listOf("com.google.ar.lens"), emptyMap(), "Point at something and tap the shutter to search it."),
        App("gemini", "Gemini", "ai", listOf("google gemini", "bard", "\u099C\u09C7\u09AE\u09BF\u09A8\u09BF", "\u0997\u09C1\u0997\u09B2 \u099C\u09C7\u09AE\u09BF\u09A8\u09BF"), listOf("com.google.android.apps.bard"), emptyMap(), "Message box at the bottom (\"Ask Gemini\"). type_text with submit=true sends it. The answer streams in: wait 2-4 s, then get_screen and read it. New chat = the pencil/\"New chat\" icon."),
        App("playstore", "Google Play Store", "store", listOf("play store", "playstore", "google play", "\u09AA\u09CD\u09B2\u09C7 \u09B8\u09CD\u099F\u09CB\u09B0", "\u09AA\u09CD\u09B2\u09C7\u09B8\u09CD\u099F\u09CB\u09B0"), listOf("com.android.vending"), mapOf("search" to "market://search?q={q}&c=apps", "details" to "market://details?id={q}"), "For installing use install_app (taps Install and waits). Page buttons: \"Install\", \"Open\", \"Update\", \"Uninstall\". Paid apps are never bought without his yes. Use action details with a package name to open an exact app page."),
        App("files", "Files", "system", listOf("file manager", "files by google", "my files", "\u09AB\u09BE\u0987\u09B2 \u09AE\u09CD\u09AF\u09BE\u09A8\u09C7\u099C\u09BE\u09B0", "\u09AB\u09BE\u0987\u09B2\u09B8"), listOf("com.google.android.apps.nbu.files", "com.android.documentsui", "com.mi.android.globalFileexplorer"), emptyMap(), "For files prefer the file_* tools (file_search, file_open, file_share). Downloads folder = \"Downloads\"."),
        App("camera", "Camera", "system", listOf("camera app", "\u0995\u09CD\u09AF\u09BE\u09AE\u09C7\u09B0\u09BE"), listOf("com.google.android.GoogleCamera", "org.lineageos.aperture", "org.lineageos.snap", "com.android.camera", "com.android.camera2", "com.mi.android.globalcamera"), emptyMap(), "Big round button = shutter; a switch icon flips front/back. Allow camera permission with \"While using the app\"."),
        App("gallery", "Gallery", "system", listOf("gallery", "\u0997\u09CD\u09AF\u09BE\u09B2\u09BE\u09B0\u09BF"), listOf("com.miui.gallery", "com.android.gallery3d", "org.lineageos.glimpse", "com.google.android.apps.photos"), emptyMap(), "Albums tab; long-press selects."),
        App("settings", "Settings", "system", listOf("setting", "settings", "\u09B8\u09C7\u099F\u09BF\u0982\u09B8"), listOf("com.android.settings"), emptyMap(), "Search box on top finds any setting fast: type the setting name. For Wi-Fi, Bluetooth, brightness, volume use set_switch / set_brightness / set_volume."),
        App("calculator", "Calculator", "system", listOf("calculator", "calc", "\u0995\u09CD\u09AF\u09BE\u09B2\u0995\u09C1\u09B2\u09C7\u099F\u09B0"), listOf("com.google.android.calculator", "com.android.calculator2", "com.miui.calculator"), emptyMap(), "Tap the digits and operators with click_text, then \"=\"; for simple maths just answer without the app."),
        App("whatsapp", "WhatsApp", "social", listOf("wa", "wp", "whats app", "\u09B9\u09CB\u09AF\u09BC\u09BE\u099F\u09B8\u0985\u09CD\u09AF\u09BE\u09AA", "\u09B9\u09CB\u09AF\u09BC\u09BE\u099F\u09B8\u098F\u09AA", "\u09B9\u09CB\u09AF\u09BC\u09BE\u099F\u09B8 \u0985\u09CD\u09AF\u09BE\u09AA"), listOf("com.whatsapp"), mapOf("chat" to "https://wa.me/{q}"), "Prefer open_chat / read_chat / send_message (made for it). action chat with the full number (country code, digits only, e.g. 8801XXXXXXXXX) opens that chat. Labels: \"Search\", \"New chat\", tabs Chats / Updates / Calls; in a chat \"Message\" box and \"Send\". Read the whole thread before replying."),
        App("whatsappb", "WhatsApp Business", "social", listOf("whatsapp business", "wa business", "whatsapp w4b"), listOf("com.whatsapp.w4b"), mapOf("chat" to "https://wa.me/{q}"), "Same as WhatsApp; the number is for the business account."),
        App("messenger", "Messenger", "social", listOf("facebook messenger", "\u09AE\u09C7\u09B8\u09C7\u099E\u09CD\u099C\u09BE\u09B0", "\u09AE\u09C7\u09B8\u09C7\u09A8\u09CD\u099C\u09BE\u09B0"), listOf("com.facebook.orca"), emptyMap(), "Prefer open_chat / read_chat / send_message. Search box on top finds a person; the \"Message\" box and the send arrow are at the bottom. Read the thread before replying."),
        App("facebook", "Facebook", "social", listOf("fb", "face book", "\u09AB\u09C7\u09B8\u09AC\u09C1\u0995", "\u09AB\u09C7\u0987\u09B8\u09AC\u09C1\u0995"), listOf("com.facebook.katana", "com.facebook.lite"), mapOf("search" to "https://www.facebook.com/search/top/?q={q}"), "Search = magnifier on top. Tabs: Home, Friends, Video, Marketplace, Notifications, Menu. \"What's on your mind?\" starts a post (ask his yes before posting)."),
        App("instagram", "Instagram", "social", listOf("insta", "ig", "\u0987\u09A8\u09B8\u09CD\u099F\u09BE\u0997\u09CD\u09B0\u09BE\u09AE", "\u0987\u09A8\u09CD\u09B8\u099F\u09BE\u0997\u09CD\u09B0\u09BE\u09AE"), listOf("com.instagram.android"), mapOf("user" to "https://www.instagram.com/{q}/"), "action user + the username opens that profile. Bottom tabs: Home, Reels, Messages, Search, Profile. Messages = the paper-plane icon."),
        App("telegram", "Telegram", "social", listOf("tg", "\u099F\u09C7\u09B2\u09BF\u0997\u09CD\u09B0\u09BE\u09AE"), listOf("org.telegram.messenger", "org.thunderdog.challegram", "org.telegram.plus"), mapOf("user" to "https://t.me/{q}"), "action user + a username (no @) opens that chat or channel. Search = magnifier on top; \"Message\" box + send arrow at the bottom; \"Join\" for channels."),
        App("tiktok", "TikTok", "social", listOf("tik tok", "\u099F\u09BF\u0995\u099F\u0995"), listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), mapOf("search" to "https://www.tiktok.com/search?q={q}"), "Swipe up = next video. Tabs: Home, Friends, \"+\", Inbox, Profile. Search = magnifier top right."),
        App("x", "X", "social", listOf("twitter", "x twitter", "\u099F\u09C1\u0987\u099F\u09BE\u09B0"), listOf("com.twitter.android"), mapOf("search" to "https://x.com/search?q={q}"), "Post = the feather/\"+\" button; ask his yes before posting."),
        App("snapchat", "Snapchat", "social", listOf("snap", "\u09B8\u09CD\u09A8\u09CD\u09AF\u09BE\u09AA\u099A\u09CD\u09AF\u09BE\u099F"), listOf("com.snapchat.android"), emptyMap(), "Opens on the camera; swipe right for chats, left for stories."),
        App("linkedin", "LinkedIn", "social", listOf("linked in", "\u09B2\u09BF\u0982\u0995\u09A1\u0987\u09A8"), listOf("com.linkedin.android"), emptyMap(), "Search on top; tabs Home, My Network, Post, Notifications, Jobs."),
        App("reddit", "Reddit", "social", listOf("\u09B0\u09C7\u09A1\u09BF\u099F"), listOf("com.reddit.frontpage"), mapOf("search" to "https://www.reddit.com/search/?q={q}"), "Search on top; a subreddit is r/name."),
        App("discord", "Discord", "social", listOf("\u09A1\u09BF\u09B8\u0995\u09B0\u09CD\u09A1"), listOf("com.discord"), emptyMap(), "Servers on the left, channels in the list; \"Message\" box at the bottom."),
        App("imo", "imo", "social", listOf("\u0987\u09AE\u09CB", "imo app"), listOf("com.imo.android.imoim"), emptyMap(), "Chats list; tap a contact, type the message in the box; the call icons are top right."),
        App("viber", "Viber", "social", listOf("\u09AD\u09BE\u0987\u09AC\u09BE\u09B0"), listOf("com.viber.voip"), emptyMap(), "Chats list; tap one and write in the \"Message\" box."),
        App("signal", "Signal", "social", listOf("\u09B8\u09BF\u0997\u09A8\u09CD\u09AF\u09BE\u09B2"), listOf("org.thoughtcrime.securesms"), emptyMap(), "Chats list; the pencil button starts a chat."),
        App("zoom", "Zoom", "social", listOf("\u099C\u09C1\u09AE"), listOf("us.zoom.videomeetings"), emptyMap(), "\"Join\" with a meeting ID, or \"New meeting\"; allow camera and microphone."),
        App("truecaller", "Truecaller", "social", listOf("\u099F\u09CD\u09B0\u09C1\u0995\u09B2\u09BE\u09B0"), listOf("com.truecaller"), emptyMap(), "Search box on top finds a number or a name."),
        App("spotify", "Spotify", "music", listOf("\u09B8\u09CD\u09AA\u099F\u09BF\u09AB\u09BE\u0987", "spotify music"), listOf("com.spotify.music"), mapOf("search" to "spotify:search:{q}", "play" to "spotify:search:{q}"), "action play opens the search for the song; then click the first \"Song\" result and press \"Play\". Free accounts may only shuffle and get ads (say so; do not promise an exact song). Tabs: Home, Search, Your Library."),
        App("applemusic", "Apple Music", "music", listOf("apple music"), listOf("com.apple.android.music"), emptyMap(), "Search tab; tap a song to play; the mini player is at the bottom."),
        App("amazonmusic", "Amazon Music", "music", listOf("amazon music"), listOf("com.amazon.mp3"), emptyMap(), "Search on top; tap a song; the mini player has play/pause."),
        App("soundcloud", "SoundCloud", "music", listOf("\u09B8\u09BE\u0989\u09A8\u09CD\u09A1\u0995\u09CD\u09B2\u09BE\u0989\u09A1"), listOf("com.soundcloud.android"), emptyMap(), "Search tab; tap a track to play."),
        App("deezer", "Deezer", "music", emptyList(), listOf("deezer.android.app"), emptyMap(), "Search tab; tap a track to play."),
        App("shazam", "Shazam", "music", listOf("\u09B6\u09BE\u099C\u09BE\u09AE"), listOf("com.shazam.android"), emptyMap(), "Big Shazam button listens and names the song playing nearby. Allow the microphone."),
        App("jiosaavn", "JioSaavn", "music", listOf("saavn", "jio saavn"), listOf("com.jio.media.jiobeats"), emptyMap(), "Search on top; tap a song."),
        App("gaana", "Gaana", "music", emptyList(), listOf("com.gaana"), emptyMap(), "Search on top; tap a song."),
        App("wynk", "Wynk Music", "music", listOf("wynk"), listOf("com.bsbportal.music"), emptyMap(), "Search on top; tap a song."),
        App("resso", "Resso", "music", emptyList(), listOf("com.moonvideo.android.resso"), emptyMap(), "Search on top; tap a song."),
        App("audiomack", "Audiomack", "music", emptyList(), listOf("com.audiomack"), emptyMap(), "Search tab; tap a track."),
        App("boomplay", "Boomplay", "music", emptyList(), listOf("com.afmobi.boomplayer"), emptyMap(), "Search on top; tap a song."),
        App("vlc", "VLC", "music", listOf("vlc player", "\u09AD\u09BF\u098F\u09B2\u09B8\u09BF"), listOf("org.videolan.vlc"), emptyMap(), "Local music and videos: Audio / Video tabs list the files on the phone; tap one to play. Needs storage permission."),
        App("poweramp", "Poweramp", "music", emptyList(), listOf("com.maxmpz.audioplayer"), emptyMap(), "Local music player: Folders / Songs tabs; tap a song."),
        App("musicolet", "Musicolet", "music", emptyList(), listOf("in.krosbits.musicolet"), emptyMap(), "Local music player: Folders / Tracks; tap a track."),
        App("newpipe", "NewPipe", "music", listOf("new pipe", "\u09A8\u09BF\u0989\u09AA\u09BE\u0987\u09AA"), listOf("org.schabi.newpipe"), emptyMap(), "Open-source YouTube client without ads: magnifier on top to search; tap a result; the headphone icon plays audio only."),
        App("netflix", "Netflix", "video", listOf("\u09A8\u09C7\u099F\u09AB\u09CD\u09B2\u09BF\u0995\u09CD\u09B8"), listOf("com.netflix.mediaclient"), mapOf("search" to "https://www.netflix.com/search?q={q}"), "Pick the profile first; Search tab or magnifier; \"Play\" starts the title."),
        App("prime", "Prime Video", "video", listOf("amazon prime", "prime video"), listOf("com.amazon.avod.thirdpartyclient"), emptyMap(), "Search on top; \"Watch now\" / \"Play\"."),
        App("hotstar", "Disney+ Hotstar", "video", listOf("hotstar", "disney plus", "\u09B9\u099F\u09B8\u09CD\u099F\u09BE\u09B0"), listOf("in.startv.hotstar"), emptyMap(), "Search magnifier; \"Watch now\"."),
        App("hoichoi", "Hoichoi", "video", listOf("hoi choi", "\u09B9\u0987\u099A\u0987"), listOf("com.viewlift.hoichoi"), emptyMap(), "Bengali shows: search magnifier, then Play."),
        App("mxplayer", "MX Player", "video", listOf("mx player", "\u098F\u09AE\u098F\u0995\u09CD\u09B8 \u09AA\u09CD\u09B2\u09C7\u09AF\u09BC\u09BE\u09B0"), listOf("com.mxtech.videoplayer.ad"), emptyMap(), "Local videos: tap the folder, then the video."),
        App("aptoide", "Aptoide", "store", listOf("aptoide store"), listOf("cm.aptoide.pt"), emptyMap(), "Search on top; open the app page and tap \"Install\". Check the app name and developer before installing; ask his yes first."),
        App("fdroid", "F-Droid", "store", listOf("f droid", "fdroid", "\u098F\u09AB \u09A1\u09CD\u09B0\u09AF\u09BC\u09C7\u09A1"), listOf("org.fdroid.fdroid"), emptyMap(), "Open-source apps only. Search icon on top; open the app, \"Install\" (first allow installs from unknown sources if asked)."),
        App("droidify", "Droid-ify", "store", listOf("droid ify"), listOf("com.looker.droidify"), emptyMap(), "F-Droid client; search on top, open the app, \"Install\"."),
        App("apkpure", "APKPure", "store", listOf("apk pure", "apkpure app"), listOf("com.apkpure.aegon"), emptyMap(), "Search on top; open the app page, \"Download APK\" / \"Install\". Check the name, version and developer."),
        App("uptodown", "Uptodown", "store", listOf("up to down"), listOf("com.uptodown"), emptyMap(), "Search on top; open the app; \"Download\" then \"Install\"."),
        App("apkmirror", "APKMirror Installer", "store", listOf("apk mirror installer"), listOf("com.apkmirror.helper.prod"), emptyMap(), "Installs the .apkm bundles from apkmirror.com; open the downloaded file with it."),
        App("aurora", "Aurora Store", "store", listOf("aurora", "aurora store"), listOf("com.aurora.store"), emptyMap(), "Anonymous client for the Play Store catalogue; search, open an app, \"Install\"."),
        App("obtainium", "Obtainium", "store", emptyList(), listOf("dev.imranr.obtainium"), emptyMap(), "Add an app source link with \"+\", it then installs and updates it from there."),
        App("galaxystore", "Galaxy Store", "store", listOf("samsung store", "samsung galaxy store"), listOf("com.sec.android.app.samsungapps"), emptyMap(), "Search on top; \"Install\"."),
        App("getapps", "Mi GetApps", "store", listOf("getapps", "mi store", "xiaomi store"), listOf("com.xiaomi.mipicks"), emptyMap(), "Search on top; \"Install\"."),
        App("appgallery", "Huawei AppGallery", "store", listOf("appgallery", "huawei store"), listOf("com.huawei.appmarket"), emptyMap(), "Search on top; \"Install\"."),
        App("apkmirrorweb", "APKMirror (website)", "apk", listOf("apkmirror", "apk mirror", "apkmirror.com"), emptyList(), mapOf("search" to "https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s={q}"), "Website. action search opens the results in the browser. Open the newest matching release, scroll to \"Download APK\" (pick the right variant for the phone), download. Always ask his yes before the download."),
        App("apkpureweb", "APKPure (website)", "apk", listOf("apkpure.com", "apkpure website"), emptyList(), mapOf("search" to "https://apkpure.com/search?q={q}"), "Website. Open the right result, then \"Download APK\". Check the name and version; ask his yes first."),
        App("fdroidweb", "F-Droid (website)", "apk", listOf("f-droid.org", "fdroid website"), emptyList(), mapOf("search" to "https://search.f-droid.org/?q={q}&lang=en"), "Website with open-source apps; the app page has a \"Download APK\" link at the bottom."),
        App("github", "GitHub (releases)", "apk", listOf("github", "github releases", "\u0997\u09BF\u099F\u09B9\u09BE\u09AC"), emptyList(), mapOf("search" to "https://github.com/search?q={q}&type=repositories"), "Open the project, click \"Releases\" on the right, and download the .apk under \"Assets\" (choose arm64-v8a for most phones). Ask his yes before the download."),
        App("termux", "Termux", "tools", listOf("termux app", "\u099F\u09BE\u09B0\u09CD\u09AE\u09BE\u0995\u09CD\u09B8"), listOf("com.termux"), emptyMap(), "A terminal: type a command and press Enter. Use the Terminal tools of NISU AI for builds instead of typing by hand."),
        App("magisk", "Magisk", "tools", listOf("magisk app", "\u09AE\u09CD\u09AF\u09BE\u099C\u09BF\u09B8\u09CD\u0995"), listOf("com.topjohnwu.magisk"), emptyMap(), "Bottom tabs: Home, Modules, Superuser, Settings. Modules need a reboot to apply."),
        App("shizuku", "Shizuku", "tools", emptyList(), listOf("moe.shizuku.privileged.api"), emptyMap(), "Start it with \"Start via Wireless debugging\" or root; other apps then ask for permission."),
        App("chatgpt", "ChatGPT", "ai", listOf("chat gpt", "openai", "gpt", "\u099A\u09CD\u09AF\u09BE\u099F\u099C\u09BF\u09AA\u09BF\u099F\u09BF", "\u099A\u09CD\u09AF\u09BE\u099F \u099C\u09BF\u09AA\u09BF\u099F\u09BF"), listOf("com.openai.chatgpt"), mapOf("web_ask" to "https://chatgpt.com/?q={q}"), "Message box at the bottom (\"Ask anything\"): type_text with submit=true. The answer streams: wait 3-5 s, then get_screen and read the whole answer (scroll down if long). New chat = the compose icon on top. Voice = the headphone/wave button."),
        App("claude", "Claude", "ai", listOf("claude ai", "anthropic", "\u0995\u09CD\u09B2\u09A6", "\u0995\u09CD\u09B2\u09BE\u0989\u09A1", "\u0995\u09CD\u09B2\u09A1"), listOf("com.anthropic.claude"), mapOf("web_ask" to "https://claude.ai/new?q={q}"), "Message box at the bottom: type_text with submit=true. The answer streams: wait 3-5 s then get_screen; scroll to read all of it. New chat = the \"+\"/new chat button; chat history in the side menu. A login screen means he must sign in himself."),
        App("copilot", "Microsoft Copilot", "ai", listOf("copilot", "bing", "\u0995\u09CB\u09AA\u09BE\u0987\u09B2\u099F"), listOf("com.microsoft.copilot"), emptyMap(), "Message box at the bottom; type_text submit=true; wait for the answer to finish, then read it."),
        App("perplexity", "Perplexity", "ai", listOf("perplexity ai", "\u09AA\u09BE\u09B0\u09AA\u09CD\u09B2\u09C7\u0995\u09CD\u09B8\u09BF\u099F\u09BF"), listOf("ai.perplexity.app.android"), mapOf("search" to "https://www.perplexity.ai/search?q={q}"), "Search box: type a question, submit; the answer comes with sources; read it after it stops streaming."),
        App("deepseek", "DeepSeek", "ai", listOf("deep seek", "\u09A1\u09BF\u09AA\u09B8\u09BF\u0995"), listOf("com.deepseek.chat"), emptyMap(), "Message box at the bottom; \"DeepThink\" toggle = reasoning mode; type_text submit=true, wait, read."),
        App("grok", "Grok", "ai", listOf("xai", "\u0997\u09CD\u09B0\u09CB\u0995"), listOf("ai.x.grok"), emptyMap(), "Message box at the bottom; type_text submit=true, wait, read."),
        App("metaai", "Meta AI", "ai", listOf("meta ai", "\u09AE\u09C7\u099F\u09BE \u098F\u0986\u0987"), listOf("com.facebook.stella"), emptyMap(), "Also inside WhatsApp / Messenger search. Message box at the bottom; type_text submit=true."),
        App("kimi", "Kimi", "ai", listOf("kimi ai"), listOf("com.moonshot.kimichat"), emptyMap(), "Message box at the bottom; type_text submit=true, wait, read."),
        App("characterai", "Character.AI", "ai", listOf("character ai", "character.ai"), listOf("ai.character.app"), emptyMap(), "Pick a character, then use the message box at the bottom."),
        App("poe", "Poe", "ai", emptyList(), listOf("com.poe.android"), emptyMap(), "Pick a bot, then the message box at the bottom."),
        App("bkash", "bKash", "pay", listOf("bkash", "\u09AC\u09BF\u0995\u09BE\u09B6", "\u09AC\u09BF\u0995\u09BE\u09B6 \u0985\u09CD\u09AF\u09BE\u09AA"), listOf("com.bKash.customerapp"), emptyMap(), "Money apps: NEVER enter or say a PIN, never confirm a transfer or payment without his clear yes to the exact amount and number. Open it and stop at the screen he asks for."),
        App("nagad", "Nagad", "pay", listOf("\u09A8\u0997\u09A6"), listOf("com.konasl.nagad"), emptyMap(), "Same rule as bKash: no PIN, no sending money without his exact yes."),
        App("rocket", "Rocket", "pay", listOf("dbbl rocket", "\u09B0\u0995\u09C7\u099F"), listOf("com.dbbl.mbs.apps.main"), emptyMap(), "Same rule as bKash: no PIN, no sending money without his exact yes."),
        App("googlepay", "Google Wallet", "pay", listOf("google pay", "gpay", "google wallet"), listOf("com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user"), emptyMap(), "Never confirm a payment without his exact yes."),
        App("pathao", "Pathao", "daily", listOf("pathao app", "\u09AA\u09BE\u09A0\u09BE\u0993"), listOf("com.pathao.user"), emptyMap(), "Ride / food / parcel. Pick the service tab, set the pickup and destination, check the price, and ask his yes before \"Confirm\"."),
        App("uber", "Uber", "daily", listOf("\u0989\u09AC\u09BE\u09B0"), listOf("com.ubercab"), emptyMap(), "Enter the destination in \"Where to?\", pick a ride, check the price, ask his yes before confirming."),
        App("foodpanda", "foodpanda", "daily", listOf("food panda", "\u09AB\u09C1\u09A1\u09AA\u09BE\u09A8\u09CD\u09A1\u09BE"), listOf("com.global.foodpanda.android"), emptyMap(), "Search a restaurant or dish, add to cart, check the total, ask his yes before \"Place order\"."),
        App("daraz", "Daraz", "daily", listOf("\u09A6\u09BE\u09B0\u09BE\u099C"), listOf("com.daraz.android"), mapOf("search" to "https://www.daraz.com.bd/catalog/?q={q}"), "Search on top; open a product, check price and seller; ask his yes before \"Buy Now\"."),
        App("amazon", "Amazon Shopping", "daily", listOf("amazon", "amazon shopping", "\u0985\u09CD\u09AF\u09BE\u09AE\u09BE\u099C\u09A8"), listOf("com.amazon.mShop.android.shopping"), mapOf("search" to "https://www.amazon.com/s?k={q}"), "Search on top; check price; ask his yes before buying."),
        App("aliexpress", "AliExpress", "daily", listOf("ali express"), listOf("com.alibaba.aliexpresshd"), emptyMap(), "Search on top; ask his yes before ordering."),
        App("temu", "Temu", "daily", emptyList(), listOf("com.einnovation.temu"), emptyMap(), "Search on top; ask his yes before ordering."),
        App("mygp", "MyGP", "daily", listOf("my gp", "grameenphone", "\u099C\u09BF\u09AA\u09BF", "\u0997\u09CD\u09B0\u09BE\u09AE\u09C0\u09A3\u09AB\u09CB\u09A8"), listOf("com.portonics.mygp"), emptyMap(), "Balance, internet packs and recharge. Ask his yes before buying a pack."),
        App("banglalink", "MyBL", "daily", listOf("my bl", "banglalink", "\u09AC\u09BE\u0982\u09B2\u09BE\u09B2\u09BF\u0982\u0995"), listOf("com.arena.banglalinkmela.app"), emptyMap(), "Balance, internet packs and recharge. Ask his yes before buying a pack."),
        App("robi", "My Robi", "daily", listOf("my robi", "robi", "\u09B0\u09AC\u09BF"), listOf("com.robi.myrobi", "com.robi.mrobi"), emptyMap(), "Balance, internet packs and recharge. Ask his yes before buying a pack."),
        App("booking", "Booking.com", "daily", listOf("booking"), listOf("com.booking"), emptyMap(), "Search a place and dates; ask his yes before booking.")
    )

    private val byName: Map<String, App> by lazy {
        val m = HashMap<String, App>()
        for (a in APPS) {
            for (n in listOf(a.key, a.label) + a.aliases) { val k = norm(n); if (k.isNotEmpty() && !m.containsKey(k)) m[k] = a }
        }
        m
    }
    private val byPackage: Map<String, App> by lazy {
        val m = HashMap<String, App>()
        for (a in APPS) for (p in a.packages) if (!m.containsKey(p)) m[p] = a
        m
    }

    /** Exact name only (name, alias or package; no guessing). Used when the app must be found on the phone, so "Phone Cleaner" is never taken for "Phone". */
    fun resolveExact(name: String): App? {
        val raw = name.trim()
        if (raw.isEmpty()) return null
        byPackage[raw]?.let { return it }
        val k = norm(raw)
        return if (k.isEmpty()) null else byName[k]
    }

    /** The entry for a spoken / typed name or a package name, also when the request only CONTAINS a known name; null when the app is not in the list. */
    fun resolve(name: String): App? {
        val raw = name.trim()
        if (raw.isEmpty()) return null
        byPackage[raw]?.let { return it }
        val k = norm(raw)
        if (k.isEmpty()) return null
        byName[k]?.let { return it }
        // "open the youtube app", "youtube music please": the longest known name (4+ characters) that is contained in the request
        return byName.entries.filter { it.key.length >= 4 && k.contains(it.key) }.maxByOrNull { it.key.length }?.value
    }

    fun byPackageName(pkg: String): App? = byPackage[pkg]

    /** URL-encodes [text] for a link: spaces become %20 (valid in queries and in spotify: links). */
    fun enc(text: String): String = URLEncoder.encode(text.trim(), "UTF-8").replace("+", "%20")

    /** true when this action's link needs the text (search words, a number, a user name). */
    fun needsText(app: App, action: String): Boolean = app.links[action]?.contains("{q}") == true

    /** The ready link for [action] ("search", "play", ...), or null when the app has no such shortcut. */
    fun link(app: App, action: String, text: String): String? {
        val t = app.links[action.lowercase().trim()] ?: return null
        return t.replace("{q}", enc(text))
    }

    /** Playbook of one app: its own text, the group rule and the general rules. */
    fun guide(app: App): String = app.how + " " + (GROUP_HOW[app.group] ?: "") + " " + GENERAL

    /** Short hint returned right after open_app (kept small so the answer stays fast). */
    fun tip(app: App): String = app.how

    /** What to do after a shortcut opened the page. */
    fun nextStep(action: String): String = when (action.lowercase().trim()) {
        "play", "search" -> "The results are open. Pick the right one from the returned screen with click_text (exact title); do not say it is playing or done until the screen shows it."
        "navigate" -> "Directions are open. If it only shows the route, click \"Start\". Say the place and the time or distance shown."
        "chat", "user" -> "The page is open. Read the screen, then continue with the app's own box and Send button; ask for his yes before sending text he did not dictate."
        "details" -> "The page is open. For Play Store pages use install_app to install; never buy a paid app."
        "web_ask" -> "The question is in the page; wait 3-5 s for the answer to finish, then get_screen and read it."
        else -> "Read the returned screen before the next step."
    }

    /** Names of the shortcuts the app has, for the guide result. */
    fun actions(app: App): List<String> = app.links.keys.toList()
}
