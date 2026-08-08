# قاعدة البيانات — My Video Library

توثيق كامل لبنية قاعدة البيانات، آلية التشفير، الجداول، الفهارس، وتاريخ الترحيلات
(Migrations)، مع سكربت SQL جاهز لإعادة بناء المخطّط (schema) يدويًا.

---

## 1. نظرة عامة

| العنصر | القيمة |
|--------|--------|
| المحرّك | **Room 2.6.1** فوق **SQLCipher 4.5.4** (SQLite مشفّر) |
| اسم الملف | `my_video_library.db` (داخل التخزين الخاص للتطبيق) |
| رقم الإصدار الحالي | **15** |
| التشفير | AES — المفتاح لا يُخزَّن أبدًا كنص صريح |
| مصدر المفتاح | `DatabaseKeyManager` → عبارة مرور عشوائية محفوظة في `EncryptedSharedPreferences` المدعومة بـ **Android Keystore** |
| فئة القاعدة | `com.myvideolibrary.app.data.local.AppDatabase` |
| حقن التبعيات | Hilt — `com.myvideolibrary.app.di.DatabaseModule` |

### كيف تُفتح القاعدة (Hilt)

```kotlin
SQLiteDatabase.loadLibs(context)                 // تحميل مكتبات SQLCipher الأصلية
val passphrase = keyManager.getOrCreatePassphrase()
val factory = SupportFactory(passphrase)         // net.sqlcipher.database.SupportFactory

Room.databaseBuilder(context, AppDatabase::class.java, "my_video_library.db")
    .openHelperFactory(factory)                  // ← التشفير
    .addMigrations(MIGRATION_1_2, … , MIGRATION_14_15)
    .fallbackToDestructiveMigrationOnDowngrade()
    .build()
```

> **مهم:** عبارة المرور مربوطة بالجهاز عبر Android Keystore. لا يمكن فتح ملف
> `my_video_library.db` على جهاز آخر أو استخراجه دون هذا المفتاح. النسخ الاحتياطي
> يتم عبر `BackupManager` (تصدير/استيراد منطقي) وليس بنسخ ملف القاعدة مباشرةً.

---

## 2. الكيانات (Entities) والجداول

سبعة كيانات موزّعة على سبعة جداول:

| الكيان | الجدول | الغرض |
|--------|--------|-------|
| `VideoEntity` | `videos` | الكيان المركزي — كل مقطع/صوت/صورة في المكتبة |
| `FolderEntity` | `folders` | مجلدات لتنظيم المقاطع |
| `DownloadEntity` | `downloads` | مهام التنزيل (طابور/سجل/استئناف) |
| `SettingsEntity` | `settings` | صف إعدادات مفرد (Singleton، `id = 1`) |
| `PlaylistEntity` | `playlists` | قوائم التشغيل |
| `PlaylistVideoEntity` | `playlist_videos` | عضوية المقاطع في قوائم التشغيل + الترتيب |
| `SavedSearchEntity` | `saved_searches` | لقطات محفوظة لحالة الفلاتر + الفرز |

---

## 3. مخطّط الجداول (v15)

### 3.1 `videos` — الجدول المركزي

| العمود | النوع | ملاحظات |
|--------|------|---------|
| `id` | INTEGER PK AUTOINCREMENT | المعرّف |
| `title` | TEXT NOT NULL | العنوان |
| `description` | TEXT | وصف اختياري |
| `thumbnail_path` | TEXT | مسار الغلاف |
| `local_path` | TEXT NOT NULL | مسار الملف أو `content://` |
| `source` | TEXT NOT NULL | مُعرّف المزوّد (`VideoSource.id`) |
| `media_type` | TEXT NOT NULL DEFAULT 'video' | `video` / `audio` / `image` |
| `source_url` | TEXT | الرابط الأصلي إن وُجد |
| `folder_id` | INTEGER | FK → `folders.id` (ON DELETE SET NULL) |
| `category` | TEXT | اسم التصنيف |
| `tags` | TEXT | وسوم مفصولة بفواصل |
| `duration` | INTEGER | المدة بالمللي ثانية |
| `file_size` | INTEGER | الحجم بالبايت |
| `quality` | TEXT | مثل "1080p" |
| `width` / `height` | INTEGER | الأبعاد |
| `created_date` | INTEGER NOT NULL | وقت الإضافة (epoch ms) |
| `last_played_position` | INTEGER | موضع الاستئناف (ms) — *لم يعد يُستخدم للاستئناف؛ التشغيل يبدأ من الصفر* |
| `last_played_date` | INTEGER | آخر تشغيل (للأحدث مشاهدة) |
| `is_favorite` | INTEGER | مفضّل |
| `is_locked` | INTEGER | **مُهمَل** — كانت لميزة «الفيديوهات الخاصة» المنفصلة التي أُزيلت؛ لم يعد يُفلتر عليه |
| `is_private` | INTEGER | علم قديم غير مستخدم في الواجهة |
| `is_link_only` | INTEGER | صف رابط فقط (يُبثّ ولا يُخزَّن) |
| `play_count` | INTEGER | عدد مرات التشغيل |
| `content_hash` | TEXT | بصمة لكشف التكرار |

**الفهارس:** `folder_id`, `is_favorite`, `created_date`, `content_hash`,
`is_locked`, `category`, `source`, `media_type`, `play_count`, `last_played_date`.

### 3.2 `folders`
| العمود | النوع | ملاحظات |
|--------|------|---------|
| `id` | INTEGER PK AUTOINCREMENT | |
| `name` | TEXT NOT NULL | **فهرس فريد** |
| `color` | INTEGER | لون اختياري |
| `created_date` | INTEGER NOT NULL | |

### 3.3 `downloads`
الأعمدة: `id`, `video_id`, `title` (NOT NULL), `source` (NOT NULL),
`source_url` (NOT NULL), `download_url`, `audio_url`, `thumbnail_url`,
`image_urls`, `kind` (NOT NULL DEFAULT 'full'), `dest_path`, `status` (NOT NULL),
`progress`, `downloaded_bytes`, `total_bytes`, `download_speed`, `error_message`,
`retry_count`, `download_date` (NOT NULL). **فهارس:** `status`, `download_date`.

### 3.4 `settings` — صف مفرد (`id = 1`)
أهم الأعمدة: `theme`, `language`, `storage_path`, `wifi_only_downloads`,
`max_concurrent_downloads`, `view_mode`, `sort_order`, `app_lock_enabled`,
`pin_hash`, `biometric_enabled`, `hide_preview_in_recents`, `prevent_screenshots`,
`auto_cleanup_enabled`, `category_order`, `hidden_categories`,
`category_passwords`, `manage_categories_password`, `private_vault_password`,
`end_of_clip_action`.

### 3.5 `playlists` / `playlist_videos`
- `playlists`: `id`, `name` (NOT NULL), `created_date` (NOT NULL).
- `playlist_videos`: `id`, `playlist_id` (NOT NULL), `video_id` (NOT NULL),
  `position` (NOT NULL). **فهرس** على `playlist_id` و**فهرس فريد** على
  (`playlist_id`, `video_id`).

### 3.6 `saved_searches`
`id`, `name` (NOT NULL), `created_date` (NOT NULL), `search`, `favorites_only`,
`protected_mode`, `sources`, `categories`, `media_types`, `tags`, `sort_order`.
الفلاتر متعدّدة القيم تُخزَّن مفصولة بأسطر جديدة (`\n`).

---

## 4. صيغ الحقول المُسلسَلة (مهمّة)

بعض الحقول نصية لكنها تحمل بنية داخلية:

| الحقل | الصيغة | مثال |
|-------|--------|------|
| `videos.tags` | وسوم مفصولة بفواصل | `سفر,عائلة,2024` |
| `settings.category_order` | أسماء مفصولة بأسطر جديدة | `سياحة\nذكريات\nحكم` |
| `settings.hidden_categories` | أسماء التصنيفات المخفية، سطر لكل اسم | `اجتماعية` |
| `settings.category_passwords` | **`الاسم\tبصمة_sha256\tالنمط`** لكل سطر | `اجتماعية\t9f86d0…\tobscured` |
| `saved_searches.*` (مجموعات) | قيم مفصولة بأسطر جديدة | `tiktok\nyoutube` |

### أنماط حماية التصنيف (`category_passwords`)
الحقل الثالث `النمط` من `CategoryProtectionMode`:
- `visible` — الغلاف يظهر، والفتح يتطلب كلمة المرور.
- `hidden` — التصنيف مُستبعَد من المكتبة (يبقى اسمه في `hidden_categories` أيضًا).
- `obscured` — الغلاف مموّه بنفس الحجم، والفتح يتطلب كلمة المرور.

> توافق خلفي: الأسطر القديمة بحقلين فقط (`الاسم\tبصمة`) تُقرأ كـ `obscured`.
> البصمة SHA-256 (hex). المنطق كله في `util/CategorySecurity.kt`.

---

## 5. تاريخ الترحيلات (Migrations)

كلها في `data/local/Migrations.kt` ومُسجّلة في `di/DatabaseModule.kt`.

| الترحيل | التغيير |
|---------|---------|
| 1→2 | `downloads.audio_url` (تنزيل مدموج فيديو+صوت) |
| 2→3 | `videos.is_locked` |
| 3→4 | `videos.is_link_only` |
| 4→5 | `settings.category_order` |
| 5→6 | `downloads.kind` |
| 6→7 | `videos.media_type` |
| 7→8 | فهارس على `videos` (is_locked/category/source/media_type/play_count/last_played_date) |
| 8→9 | `settings.end_of_clip_action` |
| 9→10 | `settings.hidden_categories` + `settings.category_passwords` |
| 10→11 | `settings.manage_categories_password` |
| 11→12 | `downloads.image_urls` (سلايدشو) |
| 12→13 | جدولا `playlists` و`playlist_videos` + فهارسهما |
| 13→14 | جدول `saved_searches` |
| 14→15 | `videos.is_private` + `settings.private_vault_password` |

> **ملاحظة:** أنماط الحماية الثلاثة أُضيفت **دون ترحيل جديد** — النمط يُخزَّن كحقل
> ثالث داخل نص `category_passwords` الموجود، والأسطر القديمة تبقى صالحة.

### إضافة ترحيل جديد (الخطوات)
1. زد رقم الإصدار في `AppDatabase.@Database(version = N)`.
2. عرّف `val MIGRATION_(N-1)_N = object : Migration(N-1, N) { … }` في `Migrations.kt`.
3. سجّله في `DatabaseModule.addMigrations(...)`.
4. حدّث الكيان (Entity) المعني ليطابق SQL الجديد.
5. اختبر الترقية من نسخة سابقة (لا تعتمد على `fallbackToDestructiveMigration`).

---

## 6. سكربت بناء المخطّط يدويًا (v15)

سكربت SQL تمثيلي لإنشاء القاعدة من الصفر (لأدوات فحص/تصحيح خارجية على نسخة غير
مشفّرة). داخل التطبيق يتولّى Room الإنشاء تلقائيًا.

```sql
CREATE TABLE folders (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  name TEXT NOT NULL,
  color INTEGER,
  created_date INTEGER NOT NULL
);
CREATE UNIQUE INDEX index_folders_name ON folders(name);

CREATE TABLE videos (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  thumbnail_path TEXT,
  local_path TEXT NOT NULL,
  source TEXT NOT NULL,
  media_type TEXT NOT NULL DEFAULT 'video',
  source_url TEXT,
  folder_id INTEGER,
  category TEXT,
  tags TEXT,
  duration INTEGER NOT NULL DEFAULT 0,
  file_size INTEGER NOT NULL DEFAULT 0,
  quality TEXT,
  width INTEGER NOT NULL DEFAULT 0,
  height INTEGER NOT NULL DEFAULT 0,
  created_date INTEGER NOT NULL,
  last_played_position INTEGER NOT NULL DEFAULT 0,
  last_played_date INTEGER,
  is_favorite INTEGER NOT NULL DEFAULT 0,
  is_locked INTEGER NOT NULL DEFAULT 0,
  is_private INTEGER NOT NULL DEFAULT 0,
  is_link_only INTEGER NOT NULL DEFAULT 0,
  play_count INTEGER NOT NULL DEFAULT 0,
  content_hash TEXT,
  FOREIGN KEY(folder_id) REFERENCES folders(id) ON DELETE SET NULL
);
CREATE INDEX index_videos_folder_id ON videos(folder_id);
CREATE INDEX index_videos_is_favorite ON videos(is_favorite);
CREATE INDEX index_videos_created_date ON videos(created_date);
CREATE INDEX index_videos_content_hash ON videos(content_hash);
CREATE INDEX index_videos_is_locked ON videos(is_locked);
CREATE INDEX index_videos_category ON videos(category);
CREATE INDEX index_videos_source ON videos(source);
CREATE INDEX index_videos_media_type ON videos(media_type);
CREATE INDEX index_videos_play_count ON videos(play_count);
CREATE INDEX index_videos_last_played_date ON videos(last_played_date);

CREATE TABLE downloads (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  video_id INTEGER,
  title TEXT NOT NULL,
  source TEXT NOT NULL,
  source_url TEXT NOT NULL,
  download_url TEXT,
  audio_url TEXT,
  thumbnail_url TEXT,
  image_urls TEXT,
  kind TEXT NOT NULL DEFAULT 'full',
  dest_path TEXT,
  status TEXT NOT NULL,
  progress INTEGER NOT NULL DEFAULT 0,
  downloaded_bytes INTEGER NOT NULL DEFAULT 0,
  total_bytes INTEGER NOT NULL DEFAULT 0,
  download_speed INTEGER NOT NULL DEFAULT 0,
  error_message TEXT,
  retry_count INTEGER NOT NULL DEFAULT 0,
  download_date INTEGER NOT NULL
);
CREATE INDEX index_downloads_status ON downloads(status);
CREATE INDEX index_downloads_download_date ON downloads(download_date);

CREATE TABLE settings (
  id INTEGER PRIMARY KEY NOT NULL,
  theme TEXT NOT NULL DEFAULT 'system',
  language TEXT NOT NULL DEFAULT 'system',
  storage_path TEXT,
  wifi_only_downloads INTEGER NOT NULL DEFAULT 0,
  max_concurrent_downloads INTEGER NOT NULL DEFAULT 2,
  view_mode TEXT NOT NULL DEFAULT 'grid',
  sort_order TEXT NOT NULL DEFAULT 'date_desc',
  app_lock_enabled INTEGER NOT NULL DEFAULT 0,
  pin_hash TEXT,
  biometric_enabled INTEGER NOT NULL DEFAULT 0,
  hide_preview_in_recents INTEGER NOT NULL DEFAULT 1,
  prevent_screenshots INTEGER NOT NULL DEFAULT 1,
  auto_cleanup_enabled INTEGER NOT NULL DEFAULT 0,
  category_order TEXT,
  hidden_categories TEXT,
  category_passwords TEXT,
  manage_categories_password TEXT,
  private_vault_password TEXT,
  end_of_clip_action TEXT NOT NULL DEFAULT 'next'
);
INSERT INTO settings(id) VALUES (1);   -- الصف المفرد

CREATE TABLE playlists (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  name TEXT NOT NULL,
  created_date INTEGER NOT NULL
);

CREATE TABLE playlist_videos (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  playlist_id INTEGER NOT NULL,
  video_id INTEGER NOT NULL,
  position INTEGER NOT NULL
);
CREATE INDEX index_playlist_videos_playlist_id ON playlist_videos(playlist_id);
CREATE UNIQUE INDEX index_playlist_videos_playlist_id_video_id
  ON playlist_videos(playlist_id, video_id);

CREATE TABLE saved_searches (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  name TEXT NOT NULL,
  created_date INTEGER NOT NULL,
  search TEXT,
  favorites_only INTEGER NOT NULL DEFAULT 0,
  protected_mode INTEGER NOT NULL DEFAULT 0,
  sources TEXT,
  categories TEXT,
  media_types TEXT,
  tags TEXT,
  sort_order TEXT
);
```

> المصدر المرجعي الرسمي للمخطّط هو تعريفات `@Entity` في
> `data/local/entity/` وملفات `Migrations.kt`. المخطّط أعلاه للتوضيح والفحص فقط.

---

## 7. طبقة الوصول للبيانات

- **DAOs:** `data/local/dao/` (Video/Folder/Download/Settings/Playlist/SavedSearch).
- **الاستعلام الديناميكي للمكتبة:** `data/repository/LibraryQuery.kt` يبني استعلام
  `RawQuery` مُعامَل (parameter-bound) للفلاتر/الفرز، ويُغذّي **Paging 3** عبر
  `VideoDao.pagingSource`.
- **المستودعات (Repositories):** `data/repository/` تغلّف الـDAOs وتُخرج `Flow`.
- **النسخ الاحتياطي:** `data/backup/BackupManager.kt` (تصدير/استيراد منطقي).
