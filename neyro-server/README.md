# Neyrogram admin server

O'z qurilmalaringizdagi Neyrogram'ni bitta joydan ko'rish va boshqarish.
Zero dependency, faqat Node.js 18+ kerak.

## Ishga tushirish (serveringizda)

    node server.js

Birinchi ishga tushganda `config.json` yaratiladi va konsolda **Admin token**
hamda **Pair token** chiqadi. Ularni saqlab qo'ying.

Doimiy ishlashi uchun (systemd, pm2 yoki):

    nohup node server.js > neyro.log 2>&1 &

Port standart 8787. O'zgartirish: `config.json` dagi `port`, yoki `PORT=9000 node server.js`.

## Admin panel

Brauzerda: `http://<server-ip>:8787/admin` → Admin tokenni kiriting.
Qurilmalar ro'yxati, holati (online, o'qilmagan xabarlar) va buyruqlar shu yerda.

## Rollar (admin / viewer)

Server ikki token beradi: **Admin token** (to'liq — buyruq berish, o'chirish) va
**Viewer token** (faqat ko'rish — qurilmalar, statistika va chatlarni ko'radi,
lekin buyruq bera olmaydi). Panelga qaysi tokenni kiritsangiz, shu huquq beriladi.
Yozish amallariga viewer tokeni bilan urinish 403 qaytaradi.

## Statistika

Har bir qurilma kartasida **Statistika** tugmasi bor: birinchi ulanish vaqti,
taxminiy faol vaqt, beat soni, akkauntlar va hozirgi o'qilmagan xabarlar.
Sarlavhada umumiy `N qurilma · M online` ko'rsatiladi. API: `GET /api/stats`
(umumiy) yoki `GET /api/stats?device=<id>` (bitta qurilma).

## Qurilmalarni ulash

Har bir telefonda: Neyrogram → Sozlamalar → Neyrogram → (keyingi versiyada qo'shiladi)
"Masofaviy boshqaruv" → server manzili va **Pair token**ni kiriting.

> ESLATMA: bu faqat O'ZINGIZNING qurilmalaringiz uchun. Server va tokenlar
> sizda. Boshqa odamlarning qurilmalarini ulab bo'lmaydi.

## Xavfsizlik

- Admin token uchun **brute-force himoya** bor: bitta IP 10 marta xato token
  kiritsa, 15 daqiqaga bloklanadi.
- Umumiy **rate-limit**: har IP daqiqasiga 1200 so'rov (polling uchun keng, lekin
  suiiste'molni to'xtatadi).
- `config.json` va `data.json` ni maxfiy saqlang (tokenlar bor).
- Serverni HTTPS orqasida (nginx reverse proxy) ishlatish tavsiya etiladi.
- "Loglarni yuborish" buyrug'i log fayllarini serverga yuklaydi — ularda
  shaxsiy ma'lumot bo'lishi mumkin, `logs/` papkasini himoyalang.
