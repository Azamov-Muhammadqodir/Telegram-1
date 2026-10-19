"""Neyrogram matnlarini en (standart), ru, uz tillariga yozadi va brend matnlarini barcha tillarda almashtiradi."""
import os
import re
import sys

RES = sys.argv[1]
BS = chr(92)

EN = {
    "NeyroSettings": "Neyrogram",
    "NeyroSettingsInfo": "Ghost mode, hidden chats and more",
    "NeyroGhostHeader": "Ghost mode",
    "NeyroGhostAll": "Enable full ghost mode",
    "NeyroGhostOnline": "Hide online status",
    "NeyroGhostOnlineInfo": "Others will never see you as \"online\"",
    "NeyroGhostRead": "Don't send read receipts",
    "NeyroGhostReadInfo": "Senders won't know you've read their messages",
    "NeyroGhostTyping": "Hide \"typing...\"",
    "NeyroGhostTypingInfo": "Nobody will see that you're typing",
    "NeyroGhostStories": "Hide story views",
    "NeyroGhostStoriesInfo": "You won't appear in the viewers list of stories",
    "NeyroGhostFooter": "Without read receipts, messages also stay unread on your other devices.",
    "NeyroMessagesHeader": "Messages",
    "NeyroSaveDeleted": "Keep deleted messages",
    "NeyroSaveDeletedInfo": "Messages deleted by others stay with a \"deleted\" mark",
    "NeyroSaveEdits": "Keep edit history",
    "NeyroSaveEditsInfo": "Previous versions of edited messages are saved",
    "NeyroForwardNoAuthor": "Forward without author",
    "NeyroForwardNoAuthorInfo": "Forwarded messages won't show the \"Forwarded from\" header",
    "NeyroPrivacyHeader": "Privacy",
    "NeyroHiddenChats": "Hidden chats",
    "NeyroHiddenChatsFooter": "To hide a chat, open it and choose \"Hide chat\" from the ⋮ menu.",
    "NeyroHiddenChatsEmpty": "No hidden chats yet.",
    "NeyroHiddenChatsHint": "Long-press a chat to unhide it.",
    "NeyroHideChat": "Hide chat",
    "NeyroUnhideChat": "Unhide chat",
    "NeyroUnhideChatConfirm": "This chat will appear in the chat list again.",
    "NeyroChatHidden": "Chat hidden",
    "NeyroChatUnhidden": "Chat is visible in the list again",
    "NeyroHiddenCreatePin": "Create a PIN",
    "NeyroHiddenCreatePinInfo": "Choose a PIN of at least 4 digits to open hidden chats.",
    "NeyroHiddenEnterPin": "Enter PIN",
    "NeyroHiddenPinTooShort": "The PIN must have at least 4 digits",
    "NeyroHiddenWrongPin": "Wrong PIN",
    "NeyroMediaHeader": "Media",
    "NeyroFastDownload": "Faster downloads",
    "NeyroFastDownloadInfo": "Files are downloaded in bigger parallel chunks",
    "NeyroInterfaceHeader": "Interface",
    "NeyroSystemFont": "Use system font",
    "NeyroTranslateSettings": "Translation settings",
    "NeyroRestartRequired": "The change fully applies after restarting the app",
    "NeyroDataHeader": "Data",
    "NeyroClearDeleted": "Clear deleted message marks",
    "NeyroClearEdits": "Clear edit history",
    "NeyroClearConfirm": "This action cannot be undone.",
    "NeyroCleared": "Cleared",
    "NeyroAbout": "Neyrogram is an unofficial Telegram client built on the Telegram API.",
    "NeyroDeletedMark": "deleted",
    "NeyroEditHistory": "Edit history",
    "NeyroEditHistoryCurrent": "Current version",
    "NeyroSaveSticker": "Save sticker",
    "NeyroStickerNotDownloaded": "The sticker hasn't been downloaded yet",
}

RU = {
    "NeyroSettings": "Neyrogram",
    "NeyroSettingsInfo": "Режим призрака, скрытые чаты и другое",
    "NeyroGhostHeader": "Режим призрака",
    "NeyroGhostAll": "Включить режим призрака полностью",
    "NeyroGhostOnline": "Скрывать статус «в сети»",
    "NeyroGhostOnlineInfo": "Другие никогда не увидят вас «в сети»",
    "NeyroGhostRead": "Не отправлять отметки о прочтении",
    "NeyroGhostReadInfo": "Собеседник не узнает, что вы прочитали сообщение",
    "NeyroGhostTyping": "Скрывать «печатает...»",
    "NeyroGhostTypingInfo": "Никто не увидит, что вы набираете сообщение",
    "NeyroGhostStories": "Скрывать просмотры историй",
    "NeyroGhostStoriesInfo": "Вы не появитесь в списке просмотревших историю",
    "NeyroGhostFooter": "Без отметок о прочтении сообщения остаются непрочитанными и на других ваших устройствах.",
    "NeyroMessagesHeader": "Сообщения",
    "NeyroSaveDeleted": "Сохранять удалённые сообщения",
    "NeyroSaveDeletedInfo": "Сообщения, удалённые другими, остаются с пометкой «удалено»",
    "NeyroSaveEdits": "Сохранять историю изменений",
    "NeyroSaveEditsInfo": "Старые версии изменённых сообщений сохраняются",
    "NeyroForwardNoAuthor": "Пересылка без автора",
    "NeyroForwardNoAuthorInfo": "В пересланных сообщениях не будет строки «Переслано от»",
    "NeyroPrivacyHeader": "Конфиденциальность",
    "NeyroHiddenChats": "Скрытые чаты",
    "NeyroHiddenChatsFooter": "Чтобы скрыть чат, откройте его и выберите «Скрыть чат» в меню ⋮.",
    "NeyroHiddenChatsEmpty": "Скрытых чатов пока нет.",
    "NeyroHiddenChatsHint": "Нажмите на чат и удерживайте, чтобы сделать его видимым.",
    "NeyroHideChat": "Скрыть чат",
    "NeyroUnhideChat": "Показать чат",
    "NeyroUnhideChatConfirm": "Этот чат снова появится в списке чатов.",
    "NeyroChatHidden": "Чат скрыт",
    "NeyroChatUnhidden": "Чат снова виден в списке",
    "NeyroHiddenCreatePin": "Создайте PIN-код",
    "NeyroHiddenCreatePinInfo": "Придумайте PIN-код минимум из 4 цифр для доступа к скрытым чатам.",
    "NeyroHiddenEnterPin": "Введите PIN-код",
    "NeyroHiddenPinTooShort": "PIN-код должен содержать минимум 4 цифры",
    "NeyroHiddenWrongPin": "Неверный PIN-код",
    "NeyroMediaHeader": "Медиа",
    "NeyroFastDownload": "Ускоренная загрузка",
    "NeyroFastDownloadInfo": "Файлы загружаются крупными частями параллельно",
    "NeyroInterfaceHeader": "Интерфейс",
    "NeyroSystemFont": "Системный шрифт",
    "NeyroTranslateSettings": "Настройки перевода",
    "NeyroRestartRequired": "Изменение полностью применится после перезапуска приложения",
    "NeyroDataHeader": "Данные",
    "NeyroClearDeleted": "Очистить пометки удалённых сообщений",
    "NeyroClearEdits": "Очистить историю изменений",
    "NeyroClearConfirm": "Это действие нельзя отменить.",
    "NeyroCleared": "Очищено",
    "NeyroAbout": "Neyrogram — неофициальный клиент Telegram на основе Telegram API.",
    "NeyroDeletedMark": "удалено",
    "NeyroEditHistory": "История изменений",
    "NeyroEditHistoryCurrent": "Текущая версия",
    "NeyroSaveSticker": "Сохранить стикер",
    "NeyroStickerNotDownloaded": "Стикер ещё не загружен",
}

UZ = {
    "NeyroSettings": "Neyrogram",
    "NeyroSettingsInfo": "Ghost rejimi, yashirin chatlar va boshqalar",
    "NeyroGhostHeader": "Ghost rejimi",
    "NeyroGhostAll": "Ghost rejimini to'liq yoqish",
    "NeyroGhostOnline": "Online'ni yashirish",
    "NeyroGhostOnlineInfo": "Boshqalar sizni hech qachon \"online\" holatda ko'rmaydi",
    "NeyroGhostRead": "\"O'qildi\" belgisini yubormaslik",
    "NeyroGhostReadInfo": "Xabarlarni o'qiganingiz suhbatdoshga bildirilmaydi",
    "NeyroGhostTyping": "\"Yozmoqda...\"ni yashirish",
    "NeyroGhostTypingInfo": "Xabar yozayotganingiz ko'rinmaydi",
    "NeyroGhostStories": "Story ko'rilganini yashirish",
    "NeyroGhostStoriesInfo": "Story'ni ko'rganingiz ko'rganlar ro'yxatida chiqmaydi",
    "NeyroGhostFooter": "\"O'qildi\" yuborilmasa, boshqa qurilmalaringizda ham xabarlar o'qilmagan bo'lib turadi.",
    "NeyroMessagesHeader": "Xabarlar",
    "NeyroSaveDeleted": "O'chirilgan xabarlarni saqlash",
    "NeyroSaveDeletedInfo": "Boshqalar o'chirgan xabarlar \"o'chirilgan\" belgisi bilan qoladi",
    "NeyroSaveEdits": "Tahrirlar tarixini saqlash",
    "NeyroSaveEditsInfo": "Tahrirlangan xabarning eski matnlari saqlanadi",
    "NeyroForwardNoAuthor": "Muallifsiz forward",
    "NeyroForwardNoAuthorInfo": "Forward qilingan xabarlarda \"kimdan\" yozuvi ko'rsatilmaydi",
    "NeyroPrivacyHeader": "Maxfiylik",
    "NeyroHiddenChats": "Yashirin chatlar",
    "NeyroHiddenChatsFooter": "Chatni yashirish uchun chatni oching va ⋮ menyusidan \"Chatni yashirish\"ni tanlang.",
    "NeyroHiddenChatsEmpty": "Hozircha yashirin chatlar yo'q.",
    "NeyroHiddenChatsHint": "Chatni yashirinlikdan chiqarish uchun uni uzoq bosing.",
    "NeyroHideChat": "Chatni yashirish",
    "NeyroUnhideChat": "Yashirinlikdan chiqarish",
    "NeyroUnhideChatConfirm": "Bu chat yana umumiy chatlar ro'yxatida ko'rinadi.",
    "NeyroChatHidden": "Chat yashirildi",
    "NeyroChatUnhidden": "Chat yana ro'yxatda ko'rinadi",
    "NeyroHiddenCreatePin": "PIN kod yarating",
    "NeyroHiddenCreatePinInfo": "Yashirin chatlarni ochish uchun kamida 4 raqamli PIN kod o'ylab toping.",
    "NeyroHiddenEnterPin": "PIN kodni kiriting",
    "NeyroHiddenPinTooShort": "PIN kod kamida 4 raqamdan iborat bo'lishi kerak",
    "NeyroHiddenWrongPin": "PIN kod noto'g'ri",
    "NeyroMediaHeader": "Media",
    "NeyroFastDownload": "Tezroq yuklab olish",
    "NeyroFastDownloadInfo": "Fayllar kattaroq bo'laklarda va parallel yuklanadi",
    "NeyroInterfaceHeader": "Interfeys",
    "NeyroSystemFont": "Tizim shriftidan foydalanish",
    "NeyroTranslateSettings": "Tarjima sozlamalari",
    "NeyroRestartRequired": "O'zgarish ilova qayta ishga tushganda to'liq qo'llaniladi",
    "NeyroDataHeader": "Ma'lumotlar",
    "NeyroClearDeleted": "O'chirilgan xabarlar belgilarini tozalash",
    "NeyroClearEdits": "Tahrirlar tarixini tozalash",
    "NeyroClearConfirm": "Bu amalni ortga qaytarib bo'lmaydi.",
    "NeyroCleared": "Tozalandi",
    "NeyroAbout": "Neyrogram — Telegram'ning norasmiy klienti. Telegram API asosida ishlaydi.",
    "NeyroDeletedMark": "o'chirilgan",
    "NeyroEditHistory": "Tahrirlar tarixi",
    "NeyroEditHistoryCurrent": "Hozirgi versiya",
    "NeyroSaveSticker": "Stikerni saqlash",
    "NeyroStickerNotDownloaded": "Stiker hali yuklab olinmagan",
}

INTRO = {
    "en": "Telegram, **supercharged**.\nGhost mode, hidden chats and much more.",
    "ru": "Telegram **на максимум**.\nРежим призрака, скрытые чаты и многое другое.",
    "uz": "Telegram, **yanada kuchli**.\nGhost rejimi, yashirin chatlar va boshqalar.",
}

from neyro_strings_v2 import EN2, RU2, UZ2
from neyro_strings_v3 import EN3, RU3, UZ3
from neyro_strings_v4 import EN4, RU4, UZ4

for target, extra in ((EN, EN2), (RU, RU2), (UZ, UZ2), (EN, EN3), (RU, RU3), (UZ, UZ3), (EN, EN4), (RU, RU4), (UZ, UZ4)):
    target.update(extra)

assert set(EN) == set(RU) == set(UZ)


def esc(v):
    v = v.replace('&', '&amp;').replace('<', '&lt;')
    v = v.replace("'", BS + "'").replace('"', BS + '"')
    return v.replace('\n', BS + 'n')


def line(k, v):
    return '    <string name="%s">%s</string>' % (k, esc(v))


def upsert(path, values, create=False):
    if os.path.exists(path):
        s = open(path, encoding='utf-8', newline='').read()
    else:
        assert create
        s = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
    nl = '\r\n' if '\r\n' in s else '\n'
    # eski Neyro bloklarini olib tashlaymiz
    s = re.sub(r'[ \t]*<!-- Neyrogram -->\r?\n', '', s)
    s = re.sub(r'[ \t]*<string name="Neyro[^"]*">.*?</string>\r?\n', '', s)
    for k, v in values.items():
        if k.startswith('Neyro'):
            continue
        if True:
            pat = re.compile(r'(<string name="%s">)(.*?)(</string>)' % re.escape(k), re.S)
            if pat.search(s):
                s = pat.sub(lambda m: m.group(1) + esc(v) + m.group(3), s, count=1)
                continue
            if not create:
                continue
        idx = s.rfind('</resources>')
        s = s[:idx] + line(k, v) + nl + s[idx:]
    neyro = [line(k, v) for k, v in values.items() if k.startswith('Neyro')]
    idx = s.rfind('</resources>')
    s = s[:idx] + '    <!-- Neyrogram -->' + nl + nl.join(neyro) + nl + s[idx:]
    open(path, 'w', encoding='utf-8', newline='').write(s)


brand = {"AppName": "Neyrogram", "AppNameBeta": "Neyrogram Beta", "Page1Title": "Neyrogram"}

upsert(os.path.join(RES, 'values', 'strings.xml'), {**brand, "Page1Message": INTRO["en"], **EN})
upsert(os.path.join(RES, 'values-ru', 'strings.xml'), {**brand, "Page1Message": INTRO["ru"], **RU})
os.makedirs(os.path.join(RES, 'values-uz'), exist_ok=True)
upsert(os.path.join(RES, 'values-uz', 'strings.xml'), {**brand, "Page1Message": INTRO["uz"], **UZ}, create=True)

# boshqa tillarda faqat brend nomi
for folder in os.listdir(RES):
    if folder.startswith('values-') and folder not in ('values-ru', 'values-uz'):
        p = os.path.join(RES, folder, 'strings.xml')
        if os.path.exists(p):
            s = open(p, encoding='utf-8', newline='').read()
            for k, v in brand.items():
                s = re.sub(r'(<string name="%s">)(.*?)(</string>)' % k, lambda m: m.group(1) + v + m.group(3), s, count=1)
            open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok', len(EN))
