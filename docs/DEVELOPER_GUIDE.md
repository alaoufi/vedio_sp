# دليل المطوّر — My Video Library (مكتبة الفيديو)

دليل مفصّل لبناء المشروع وتطويره وفهم بنيته. مخصّص لمطوّر Android يريد بناء التطبيق
من المصدر، أو إضافة ميزات، أو صيانة الكود.

> توثيق منفصل:
> - قاعدة البيانات ومخطّطها: **[`docs/DATABASE.md`](DATABASE.md)**
> - مفتاح التوقيع والتحديث دون فقدان البيانات ورقم النسخة: **[`docs/BUILD_AND_UPDATE.md`](BUILD_AND_UPDATE.md)**

---

## 1. ما هو التطبيق

مكتبة فيديو **شخصية، تعمل دون إنترنت (offline-first)**، لجهاز واحد: بلا حسابات،
بلا سحابة، بلا تتبّع. كل البيانات محليّة داخل **قاعدة بيانات مشفّرة** في التخزين الخاص
للتطبيق. يدعم الاستيراد من الجهاز، التنزيل من مزوّدات (TikTok/YouTube/… عبر روابط)،
مشغّل Media3، تصنيفات محميّة بكلمة مرور، قوائم تشغيل، بحث/فلاتر، محرّر صور مع OCR،
ونسخ احتياطي منطقي.

---

## 2. المتطلبات (Toolchain)

| الأداة | الإصدار |
|--------|---------|
| JDK | **17** (إلزامي لـ AGP 8.6) |
| Android Gradle Plugin | 8.6.1 |
| Gradle | 8.9 (عبر wrapper) |
| Kotlin | 1.9.24 |
| KSP | 1.9.24-1.0.20 |
| compileSdk / targetSdk | **35** |
| minSdk | **24** (أندرويد 7.0) |
| Android SDK | Platform 35 + Build-Tools |
| namespace / applicationId | `com.myvideolibrary.app` |

نسخة الـ**debug** تستخدم `applicationId` بلاحقة `.debug` فتُثبَّت جنبًا إلى جنب مع
الإصدار النهائي وتُحدّث نفسها في كل بناء.

---

## 3. البناء والتشغيل

### 3.1 محليًا
```bash
# 1) أنشئ local.properties وأشِر لـ Android SDK (أو صدّر ANDROID_HOME)
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2) بناء نسخة debug
./gradlew assembleDebug
#   الناتج: app/build/outputs/apk/debug/*.apk

# 3) تثبيت على جهاز/محاكي متصل
./gradlew installDebug

# 4) الاختبارات
./gradlew testDebugUnitTest        # وحدات
./gradlew lint                     # فحص Lint
```

مرّر رقم النسخة من سطر الأوامر (كما في CI):
```bash
./gradlew assembleDebug -PversionCode=42 -PversionName=1.0.42
```

### 3.2 CI/CD — GitHub Actions
- الملف: `.github/workflows/build.yml`.
- المشغّل: كل `push` على أي فرع + `workflow_dispatch` يدوي.
- الخطوات: JDK 17 → setup-android → `assembleDebug` → رفع الـAPK كـ**Artifact**
  ثم نشره في **Release** بوسم `apk-latest`.
- رابط تنزيل مباشر ثابت:
  `https://github.com/alaoufi/vedio_sp/releases/download/apk-latest/vedio_lb.apk`
- `versionName = 1.0.<github.run_number>`.

---

## 4. البنية المعمارية (Architecture)

نمط **MVVM** + مستودعات (Repository) + Hilt للحقن + Coroutines/Flow، وواجهات
Views/XML (ViewBinding) مع Paging 3.

```
UI (Activity/Adapter/ViewModel)
        │  StateFlow / PagingData
        ▼
Repository  ──►  Room DAO (SQLCipher)  +  Providers/Network  +  Storage
        ▲
        └── Settings / Security / Download managers
```

### شجرة الحزم (`com.myvideolibrary.app`)
| الحزمة | المحتوى |
|--------|---------|
| `ui/` | كل الشاشات: `main` (المكتبة)، `player`، `categories`، `playlists`، `search`، `downloads`، `settings`، `stats`، `importer`، `duplicates`، `imageeditor`، `provider`، `trim`، `compress`، `security`، `help`، `browser`، `share` |
| `data/local/` | `AppDatabase`، `entity/`، `dao/`، `Migrations.kt`، `DatabaseKeyManager` |
| `data/repository/` | المستودعات + `LibraryQuery` (بناء استعلام المكتبة) |
| `data/backup/` | `BackupManager` (تصدير/استيراد) |
| `data/model/` | تعدادات ونماذج (`SortOrder`, `MediaType`, `VideoSource`, `SourceFilter`, `EndOfClipAction`, …) |
| `provider/` | نظام المزوّدات: `VideoProvider`, `ProviderRegistry`، و`tiktok/youtube/instagram/snapchat/web` |
| `download/` | `DownloadManager`, `DownloadWorker` (WorkManager)، `DownloadNotifier`, `VideoMuxer` |
| `security/` | `SecurityManager`, `AppLockManager`, `ProtectedCategoriesSession`, `LicenseManager`, `BillingManager` |
| `di/` | وحدات Hilt (`DatabaseModule`, …) |
| `util/` | أدوات: `CategorySecurity`, `CategoryProtectionMode`, `BlurCoverTransformation`, `CategoryOrder`, `Formatters`, `MediaStoreScanner`, `ThumbnailGenerator`, `StorageManager`, `CoverTint`, `SlideshowEncoder`, `VideoTrimmer`, `UpdateChecker`, … |

---

## 5. الأنظمة الفرعية الرئيسية

### 5.1 المكتبة والاستعلام
- `ui/main/LibraryViewModel` يجمع الفلاتر (بحث، مجلد، مفضّل، مصدر، تصنيف، نوع،
  وسوم) + الفرز في `LibraryQuery`، ويُخرج `Flow<PagingData<VideoEntity>>`.
- `LibraryQuery.toSupportQuery()` يبني SQL مُعامَلًا آمنًا (بلا حقن SQL).
- العرض في `VideoPagingAdapter` (شبكة/قائمة) مع Glide للأغلفة وPalette للتلوين.

### 5.2 حماية التصنيفات (٣ أنماط) — ميزة محورية
الخصوصية **على مستوى التصنيف** فقط (لا توجد «فيديوهات خاصة» منفصلة). عند تفعيل
الحماية يختار المستخدم أحد ثلاثة أنماط (`util/CategoryProtectionMode`):

| النمط | السلوك في المكتبة | الفتح |
|-------|-------------------|-------|
| `VISIBLE` | الغلاف يظهر طبيعيًا | يتطلب كلمة مرور |
| `HIDDEN` | التصنيف مُستبعَد كليًا | يُفتح من «إدارة التصنيفات» بكلمة مروره |
| `OBSCURED` | الغلاف **مموّه** بنفس الحجم (`BlurCoverTransformation`) | يتطلب كلمة مرور |

- التخزين: `settings.category_passwords` بصيغة `الاسم\tبصمة_sha256\tالنمط`
  (انظر `docs/DATABASE.md §4`). المنطق في `util/CategorySecurity.kt`.
- الجلسة: `security/ProtectedCategoriesSession` يحتفظ بالتصنيفات المفكوكة في
  الذاكرة فقط للجلسة الأمامية؛ التصغير/الخروج يُعيد القفل (بدء بارد = مقفل دائمًا).
- الواجهة: `LibraryViewModel` يُخرج مجموعتين مُطبّعتين:
  - `obscuredCategories` (نمط OBSCURED) → لتمويه الغلاف في الـAdapter.
  - `lockedCategories` (VISIBLE ∪ OBSCURED) → لطلب كلمة المرور عند الفتح/القائمة.
  - التصنيفات `HIDDEN` تُستبعَد عبر `settings.hidden_categories` في `LibraryQuery`.
- طلب كلمة المرور: `MainActivity.promptCategoryUnlock` + `CategorySecurity.verify`.
- **طابور المشغّل** يُصفّى ليستبعد المقاطع في تصنيفات مقفلة، فلا يصلها السحب بلا
  كلمة مرور (`MainActivity.onVideoClick`).

### 5.3 المشغّل (`ui/player/PlayerActivity`)
Media3 ExoPlayer: سرعات تشغيل، تدوير، PiP، إيماءات سطوع/صوت، ترجمة، طابور تشغيل
تلقائي (سحب رأسي)، وإجراء نهاية المقطع (`stop`/`repeat`/`next`).
**بدء التشغيل دائمًا من الصفر** (لا استئناف) — موضع المشاهدة يُحفظ داخليًا لشريط
التقدّم و«متابعة المشاهدة» فقط ولا يُستخدم للقفز عند الفتح.

### 5.4 التنزيل والمزوّدات
- `provider/VideoProvider` (واجهة) + `ProviderRegistry` (Hilt multibinding).
- `download/DownloadWorker` عبر WorkManager: استئناف عبر HTTP Range، إشعارات
  تقدّم/سرعة، إعادة محاولة بتراجع أُسّي، ودمج فيديو+صوت في `VideoMuxer`.

### 5.5 الأمان
- قاعدة مشفّرة SQLCipher (المفتاح في Keystore) — `DatabaseKeyManager`.
- قفل التطبيق (PIN/بصمة): `security/AppLockManager` + `security/LockActivity`.
- منع لقطات الشاشة ومعاينة Recents: `applyScreenshotPolicy` + إعدادات.
- تفعيل ترخيص أوفلاين (Ed25519/BouncyCastle) + Billing (نسخة المتجر).

### 5.6 أدوات إضافية
محرّر صور + OCR (Tesseract، يشمل العربية)، قصّ فيديو بلا إعادة ترميز، ضغط HEVC،
كشف التكرار، إحصاءات، سلايدشو، ونسخ احتياطي منطقي.

---

## 6. وصفات تطوير شائعة

**إضافة عمود/جدول:** حدّث الـ`@Entity` + زد `version` + أضف `MIGRATION_x_y`
وسجّله في `DatabaseModule` (تفاصيل في `docs/DATABASE.md §5`).

**إضافة مزوّد تنزيل:** نفّذ `VideoProvider`، وسجّله في `ProviderRegistry`
(Hilt multibinding)، وأضف مطابقة الرابط في `providerForUrl`.

**إضافة إعداد:** أضف عمودًا في `SettingsEntity` (+ ترحيل)، ومرّره عبر
`SettingsRepository`، واعرضه في `ui/settings`.

**إضافة نمط حماية/تعديل منطقها:** كل شيء مركزي في `util/CategorySecurity.kt`
و`util/CategoryProtectionMode.kt` ثم الاستهلاك في `LibraryViewModel` و
`CategoriesActivity`/`VideoPagingAdapter`/`MainActivity`.

---

## 7. الاصطلاحات

- Kotlin رسمي، تعليقات تشرح «لماذا» لا «ماذا».
- خيوط: كل I/O على Coroutines؛ الواجهة تجمع `StateFlow`.
- لا تُدخِل تبعيات شبكة/تحليلات — التطبيق أوفلاين بطبيعته.
- الأسرار (كلمات المرور/الـPIN) تُخزَّن كبصمات SHA-256 فقط، والقاعدة مشفّرة.
- التعريب: `values/strings.xml` (إنجليزي) + `values-ar/strings.xml` (عربي) — أضف
  المفتاح في **كلا** الملفين.

---

## 8. البنية الأساسية للمستودع

```
vedio_sp/
├── app/
│   ├── build.gradle.kts            # إعداد الوحدة + التبعيات
│   └── src/main/
│       ├── java/com/myvideolibrary/app/   # الكود (≈111 ملف Kotlin)
│       ├── res/                    # تخطيطات، نصوص (en/ar)، أيقونات، قوائم
│       └── AndroidManifest.xml
├── gradle/libs.versions.toml       # كتالوج الإصدارات (Version Catalog)
├── build.gradle.kts / settings.gradle.kts
├── .github/workflows/build.yml     # CI: بناء + نشر Release
├── docs/
│   ├── DEVELOPER_GUIDE.md          # هذا الملف
│   └── DATABASE.md                 # مخطّط القاعدة + الترحيلات + سكربت البناء
└── README.md
```

---

## 9. مراجع سريعة

| أريد أن… | الملف |
|----------|-------|
| أفهم مخطّط القاعدة | `docs/DATABASE.md` |
| أعدّل منطق حماية التصنيف | `util/CategorySecurity.kt` |
| أغيّر تمويه الغلاف | `util/BlurCoverTransformation.kt` |
| أعدّل استعلام/فلاتر المكتبة | `data/repository/LibraryQuery.kt` + `ui/main/LibraryViewModel.kt` |
| أعدّل سلوك المشغّل | `ui/player/PlayerActivity.kt` |
| أضيف/أعدّل ترحيلًا | `data/local/Migrations.kt` + `di/DatabaseModule.kt` |
| أضبط CI/الإصدار | `.github/workflows/build.yml` |
