# MOZ TTS機能仕様書

作成日: 2026-06-15

この文書は、MOZ（モンスターおぼえるぜ）に実装済みのTTS読み上げ機能を、SOZなど他の学習アプリへ移植するための仕様書です。対象実装は `index.html`、Android APK用の `app/src/main/assets/index.html`、Androidブリッジの `app/src/main/java/com/example/moz/MainActivity.kt` にあります。

実装済みの挙動だけを記述します。未実装の希望仕様は「15. 制約・未実装事項」に分けています。

## 1. 概要

MOZのTTS機能は、MP3などの音声ファイルをデッキに持たせず、端末またはブラウザが提供する音声合成を使って、問題文や例文を読み上げる機能です。

主な特徴は次の通りです。

- Web版では Web Speech API の `speechSynthesis` と `SpeechSynthesisUtterance` を使う。
- Android APK版では WebViewのJavaScriptから `window.Android` ブリッジを呼び、Androidの `TextToSpeech` を使う。
- Android側の音声品質、対応言語、利用可能な音声は、端末の「優先するTTSエンジン」とインストール済み言語データに依存する。
- Android側で Google音声認識と音声合成サービスを優先TTSエンジンにすると、日本語、英語、中国語などがMOZから利用できる場合がある。
- Webアプリ側からAndroid本体のTTSエンジンそのものは切り替えない。MOZは現在選択されているAndroid TTSエンジンを利用するだけである。
- デッキに `tts_text` などの任意列があればそれを優先する。ない場合も、既存デッキの問題文から通し番号、ピンイン、発音記号などを除外して読み上げる。
- 通常バトルの問題文横に手動再生ボタンを出し、設定がONなら出題時の自動再生と繰り返し再生も行う。

## 2. 対応環境

### PCブラウザ

PCブラウザでは `window.speechSynthesis` が使える場合にWeb Speech APIで再生します。`speechSynthesis.getVoices()` で取得できる音声だけが選択候補です。

`speechSynthesis` が利用できない場合、Web版にはAndroidブリッジがないため読み上げできません。画面には「この環境ではspeechSynthesisが利用できません。」または汎用の失敗メッセージが表示されます。

### Android Chrome

Android Chromeでは、ブラウザが `speechSynthesis` に対応していればWeb Speech API経由で再生します。APK版の `window.Android` ブリッジはありません。

### Android WebView / APK版

APK版では `MainActivity.kt` が `WebView.addJavascriptInterface(WebAppInterface(), "Android")` を登録しています。JavaScript側は `window.Android` の有無でAndroid TTSブリッジを判定し、利用可能ならAndroidの `TextToSpeech` を使います。

Android WebViewで `speechSynthesis` が使えない場合でも、Android TTSブリッジがあれば読み上げできます。

### `speechSynthesis` 非対応環境

JavaScript側は次の順で再生方法を選びます。

1. 選択中の音声がAndroid音声、またはAndroid優先オプションが指定され、Androidブリッジがある場合はAndroid TTS。
2. Web Speech APIが使える場合は `SpeechSynthesisUtterance`。
3. Web Speech APIが使えずAndroidブリッジがある場合はAndroid TTS。
4. どちらも使えない場合はエラー表示。

### 音声一覧が空になる環境

Web Speech APIでは、ページ読み込み直後に `speechSynthesis.getVoices()` が空配列を返すことがあります。MOZは次の対策を入れています。

- 初期化時に `refreshVoices()` を呼ぶ。
- `speechSynthesis` の `voiceschanged` イベントで再取得する。
- 400ms、1200ms、2500ms後にも再取得する。
- 「音声一覧を再取得」ボタンで手動再取得できる。
- 音声一覧が空でもエラー扱いにせず、「音声一覧はまだ取得できていない、または端末側で利用可能な音声がありません」と表示する。

## 3. TTS設定画面

TTS設定はホーム画面の折りたたみパネルとして表示されます。

- HTML要素: `details#tts-test-panel`
- 表示名: `TTS設定`
- 実装オブジェクト名: `TtsTest`

画面に表示される主な項目は次の通りです。

| 項目 | 要素ID | 内容 |
|---|---|---|
| speechSynthesis対応状況 | `tts-available` | Web Speech APIが利用可能なら「利用可能」、なければ「利用不可」 |
| 音声数 | `tts-voice-count` | Web Speech音声とAndroid TTS音声を合算した件数 |
| Android TTS状態 | `tts-android-status` | APK版のみ、初期化中、利用可能、利用不可など |
| 自由入力の言語 | `tts-free-lang` | 試聴テキスト用の言語コード |
| 自由入力の音声カテゴリ | `tts-language-category` | 音声候補を言語カテゴリで絞り込む |
| 自由入力の音声選択 | `tts-voice-select` | 試聴用の音声選択 |
| 出題時に問題を読み上げる | `cfg-tts-auto-enabled` | バトル中の自動再生ON/OFF |
| 問題を何度も繰り返す | `cfg-tts-repeat-enabled` | 自動再生ON時のみ有効。選択肢を選ぶまで繰り返す |
| 言語未指定カードの既定言語 | `tts-card-default-lang` | `tts_lang` がないカードに使う言語 |
| 言語未指定カードの既定音声 | `tts-voice-default` | `default` スロットの音声 |
| 英語カード音声 | `tts-voice-en` | 英語スロットの音声 |
| 日本語カード音声 | `tts-voice-ja` | 日本語スロットの音声 |
| 中国語カード音声 | `tts-voice-zh` | 中国語スロットの音声 |
| 読み上げ速度 | `tts-rate` | 初期値1.0、UI上は0.5から2.0 |
| ピッチ | `tts-pitch` | 初期値1.0、UI上は0から2.0 |
| 音量 | `tts-volume` | 初期値1.0、UI上は0から1.0 |
| 読み上げ除外ルール | `tts-cleanup-*` | 通し番号、括弧注記、発音記号、ピンイン、装飾記号を除外 |
| 試聴テキスト | `tts-free-text` | 初期値 `I have a dog.` |
| 選択中の音声を試聴 | `tts-play-free` | 現在選択中のTTSで自由入力を再生 |
| Android TTSで試聴 | `tts-play-android` | Android TTSブリッジ優先で自由入力を再生 |
| 停止 | `tts-stop` | Web SpeechとAndroid TTSの停止 |
| 音声一覧を再取得 | `tts-refresh-voices` | Web Speech/Android音声を再取得 |
| 利用可能な音声一覧 | `tts-voice-list` | 音声名、言語、default、local/network情報など |

読み上げ除外ルールでは、次のチェックボックスと記号編集UIがあります。

| ルール | 要素ID | 既定 |
|---|---|---|
| 先頭の通し番号 | `tts-rule-leading-number` | ON |
| 括弧内の注記 | `tts-rule-bracketed` | ON |
| 発音記号らしい末尾 | `tts-rule-pronunciation` | ON |
| ピンインらしい末尾 | `tts-rule-pinyin` | ON |
| 装飾記号 | `tts-rule-decorative` | ON |
| 除外する装飾記号一覧 | `tts-decorative-mark-list` | `●○◎◯∙•‣▪▫■□◆◇★☆※＊*` |
| 装飾記号追加入力 | `tts-decorative-mark-input` | 空 |
| 除外プレビュー | `tts-cleanup-preview-input` / `tts-cleanup-preview-output` | 入力を正規化して表示 |

## 4. 保存される設定

TTS関連設定は、大きく分けて2か所に保存されます。

- アプリデータDB（IndexedDBまたはlocalStorage fallback）: 自動再生や除外ルールなど、バックアップJSONにも含めたい設定。
- `localStorage`: 端末・ブラウザごとに違う音声選択。エクスポートJSONには含まれない。

### DBに保存される設定

| 設定 | 保存先 | キー名 | 値の例 | 備考 |
|---|---|---|---|---|
| 出題時自動再生 | DB.d | `ttsAutoEnabled` | `true` / `false` | `DB.saveSettings()` で保存 |
| 繰り返し再生 | DB.d | `ttsRepeatEnabled` | `true` / `false` | `ttsAutoEnabled` がfalseなら保存時にfalse扱い |
| 言語未指定カードの既定言語 | DB.d | `ttsDefaultLang` | `en-US`, `ja-JP`, 空文字 | `tts_lang` がないカードで使用 |
| 読み上げ除外ルール | DB.d | `ttsCleanupRules` | `{ stripLeadingNumber: true, ... }` | 既定値は `createDefaultTtsCleanupRules()` |

`ttsCleanupRules` の構造は次の通りです。

```js
{
  stripLeadingNumber: true,
  stripBracketedAnnotations: true,
  stripPronunciationGuide: true,
  stripPinyinGuide: true,
  stripDecorativeMarks: true,
  decorativeMarks: "●○◎◯∙•‣▪▫■□◆◇★☆※＊*"
}
```

これらはフルバックアップだけでなく、「学習記録のみをエクスポート」にも含まれます。

### localStorageに保存される設定

| 設定 | 保存先 | キー名 | 値の例 | 備考 |
|---|---|---|---|---|
| 自由入力の選択音声 | localStorage | `mozTtsTestSelectedVoice` | `{"source":"android","name":"...","lang":"en-US","voiceURI":"...","androidName":"..."}` | `free` スロット用 |
| 英語カード音声 | localStorage | `mozTtsSelectedVoice.en` | 同上 | `en` スロット |
| 日本語カード音声 | localStorage | `mozTtsSelectedVoice.ja` | 同上 | `ja` スロット |
| 中国語カード音声 | localStorage | `mozTtsSelectedVoice.zh` | 同上 | `zh` スロット |
| 言語未指定カード音声 | localStorage | `mozTtsSelectedVoice.default` | 同上 | `default` スロット |
| 自由入力の音声カテゴリ | localStorage | `mozTtsVoiceLanguageCategory` | `en`, `ja`, `zh`, `other`, 空文字 | 音声カテゴリ選択 |

選択音声の保存値は `serializeVoice()` で作られます。

```js
{
  source: "web" | "android",
  name: "音声名",
  lang: "en-US",
  voiceURI: "voiceURIまたは音声名",
  androidName: "Android Voice名"
}
```

音声の復元は `matchesSavedVoice()` で行います。保存値の `source` がある場合は同じ `source` の音声だけを対象にし、言語は完全一致を求めます。そのうえで `voiceURI`、`name`、`androidName` のいずれかが一致すれば復元します。

前回の音声が見つからない場合は、`pickVoiceKey()` が以下の順でフォールバックします。

1. 現在のselect値がまだ候補にあるならそれを維持。
2. localStorage保存音声に一致するもの。
3. スロットごとの優先言語に一致する音声。
4. `default` スロットでは音声の `default`、なければ先頭。
5. その他は `findBestVoice()` または候補先頭。

### 保存されない設定

| 設定 | 状態 |
|---|---|
| `tts-rate` | DB/localStorageには保存されない。DOM初期値は1.0 |
| `tts-pitch` | DB/localStorageには保存されない。DOM初期値は1.0 |
| `tts-volume` | DB/localStorageには保存されない。DOM初期値は1.0 |
| 選択した具体的な音声 | localStorage保存のみ。エクスポートJSONには含まれない |

## 5. デッキ列仕様

TTS用列は、CSV / TSV / XLSX / ODSなどのヘッダー付き表形式で読み込まれます。ZIP内の対応ファイルも同じ処理を通るため利用できます。

通常の2列TXTインポートでは、TTS列ヘッダーを解釈する処理はありません。

| 正式列名 | 実装上の別名 | 用途 | 値の例 | 空欄時の挙動 |
|---|---|---|---|---|
| `tts_text` | `tts text`, `読み上げ`, `読み上げテキスト`, `音声テキスト` | 問題音声として読むテキスト | `communication` | 問題文 `q` を読み上げ用に正規化して読む |
| `tts_lang` | `tts lang`, `tts_language`, `読み上げ言語`, `音声言語` | 問題音声の言語コード | `en-US`, `ja-JP`, `zh-CN` | `ttsDefaultLang` または音声側の言語へフォールバック |
| `tts_auto` | `tts auto`, `auto_tts`, `自動読み上げ`, `音声自動再生` | カード側のTTS自動フラグとして読み込む | `true`, `1`, `yes`, `on`, `はい`, `有効`, `enabled` | false |
| `example_tts` | `example tts`, `例文音声`, `例文読み上げ`, `example_audio_text` | 詳細画面で再生できる例文音声 | `I have a dog.` | 例文音声欄を表示しない |
| `example_tts_lang` | `example tts lang`, `example_tts_language`, `例文音声言語`, `例文読み上げ言語` | 例文音声の言語コード | `en-US` | 問題側の `tts_lang` を使う |

読み込み後のカード内部プロパティはcamelCaseに正規化されます。

| インポート列 | 内部プロパティ |
|---|---|
| `tts_text` | `ttsText` |
| `tts_lang` | `ttsLang` |
| `tts_auto` | `ttsAuto` |
| `example_tts` | `exampleTts` |
| `example_tts_lang` | `exampleTtsLang` |

古いJSONや手書きJSONにsnake_caseプロパティが残っている場合も、`normalizeCardTtsMetadata()` と `getCardTtsText()` 系関数が `tts_text` などを読めるようになっています。

## 6. 読み上げ対象の決定ロジック

問題文読み上げは `getCardSpeakableText(card)` で決まります。

優先順位は次の通りです。

1. `getCardTtsText(card)` が返す明示的なTTSテキストを使う。
   - `card.ttsText` または `card.tts_text`。
2. 明示的TTSテキストがない場合、カードの問題文 `card.q` を使う。
3. `card.q` は `normalizeQuestionTextForTts()` で読み上げ用に正規化する。
4. 正規化結果が空なら元の問題文を返す。

疑似コード:

```js
function getCardSpeakableText(card) {
  const explicit = getCardTtsText(card);
  if (explicit) return explicit;
  return normalizeQuestionTextForTts(card.q);
}
```

現在のWeb Speech再生には明示的な文字数制限はありません。Android TTSブリッジ側では、`MainActivity.kt` で `text.trim().take(1000)` として最大1000文字に切り詰めています。

### 読み上げ除外ルール

`normalizeQuestionTextForTts()` は最大3回ループし、次を順に適用します。

1. `stripLeadingQuestionNumberForTts()`
   - `No.0036 communication`
   - `●0036 communication`
   - `第1問 ...`
   - `0036 communication`
   - 丸数字など
2. `stripBracketedAnnotationsForTts()`
   - 末尾または先頭の `(...)`, `（...）`, `[...]`, `【...】`, `〈...〉`, `{...}` など。
   - `/.../` 形式も末尾注記として扱う。
3. `stripTrailingPronunciationGuideForTts()`
   - IPAらしい末尾の `/.../`, `[...]`, `(...)`、またはIPA文字を含む末尾トークン。
4. `stripTrailingPinyinForTts()`
   - 中国語文字を含む問題文に対して、末尾のピンインらしい部分を除去。
   - `chúnzhēn`, `chún・zhēn`, `chúnz // hēn`, `chúnz ∥ hēn` などを想定。
5. `stripDecorativeMarksForTts()`
   - 設定された装飾記号を除去。
   - 記号は文頭・文末だけでなく、問題文途中でも空白に置換する。

例:

| 入力 | 出力 |
|---|---|
| `纯真 chúnzhēn` | `纯真` |
| `纯真 chún・zhēn` | `纯真` |
| `No.0036 communication [kəmjùːnəkéɪʃən]` | `communication` |
| `●0036 communication [kəmjùːnəkéɪʃən]` | `communication` |
| `纯真－chúnzhēn` | `－` を装飾記号に追加していれば `纯真` |

### 例文TTS

`example_tts` は通常バトルの問題読み上げには使われません。詳細画面の「例文音声」欄で手動再生するためのテキストです。

## 7. 言語コードと音声選択

### 言語正規化

`normalizeLang(lang)` は `_` を `-` に置換し、小文字化します。

`getLanguageRoot(lang)` は言語ルートを取り出します。

- `en-US` -> `en`
- `ja-JP` -> `ja`
- `zh-CN` -> `zh`
- `zh-TW` -> `zh`
- `cmn`, `yue`, `wuu` は `zh` として扱う。
- `in` は `id` として扱う。

### 音声スロット

`getSlotForLang(lang)` は次の分類を返します。

| 条件 | スロット |
|---|---|
| `en...` | `en` |
| `ja...` | `ja` |
| `zh...` | `zh` |
| その他 | `default` |

`tts_lang` が未指定のカードは `default` スロットを使います。

### 優先言語

`getPreferredLangsForSlot(slot)` の優先順は次の通りです。

| スロット | 優先言語 |
|---|---|
| `en` | `en-US`, `en-GB`, `en` |
| `ja` | `ja-JP`, `ja` |
| `zh` | `zh-CN`, `zh-TW`, `zh` |
| `default` | `tts-card-default-lang` の値。空なら優先なし |
| `free` | `tts-free-lang` の値。空なら `en-US`, `en-GB`, `en` |

### 音声検索

`findBestVoice(lang, options)` は次の順で音声を選びます。

1. `source` が指定されていれば `web` または `android` に絞る。
2. `lang` が指定されていれば、完全一致する音声を探す。
3. 完全一致がなければ、言語ルート一致の音声を探す。
4. 言語一致がなければ `default` 音声。
5. それもなければ先頭の音声。

`resolveVoice(lang, options)` は次の順で再生に使う音声を決めます。

1. `preferAndroid` がtrueならAndroid音声から探す。
2. `voiceSlot` または `getSlotForLang(lang)` の選択済み音声を使う。
3. 選択済み音声があり、スロットが `free`、または `lang` 未指定、または言語ルートが合うなら採用。
4. `findBestVoice(lang)`。
5. Android音声の先頭候補。
6. 全音声の先頭候補。

Web Speechでは `utterance.lang` に `webVoice.lang || lang || "en-US"` を設定し、`utterance.voice` にはWeb Speech音声の `rawVoice` を設定します。Android音声の場合は `utterance.voice` には使わず、Androidブリッジに音声名を渡します。

## 8. 再生処理

### Web Speech API

`TtsTest.speakWithWebSpeech(content, lang, voice, options)` が担当します。

処理概要:

1. `speechSynthesis` と `SpeechSynthesisUtterance` の有無を確認。
2. `SpeechSynthesisUtterance(content)` を作成。
3. `utterance.lang` を設定。
4. Web音声があれば `utterance.voice = webVoice.rawVoice`。
5. `utterance.rate`, `utterance.pitch`, `utterance.volume` をUIから読む。
6. `speechSynthesis.cancel()` を呼んで既存再生を止める。
7. `speechSynthesis.speak(utterance)` を呼ぶ。
8. `onstart`, `onend`, `onerror` でステータス表示とコールバックを処理。

Web側の値範囲:

| 値 | UI範囲 | `readSetting()` の安全範囲 |
|---|---|---|
| rate | 0.5から2.0 | 0.1から10 |
| pitch | 0から2.0 | 0から2 |
| volume | 0から1.0 | 0から1 |

### Android TTS

JavaScript側は `TtsTest.speakWithAndroid(content, lang, voice, options)` を呼びます。

Android側ブリッジ:

| JSから呼ぶメソッド | Kotlin側 | 役割 |
|---|---|---|
| `window.Android.getAndroidTtsStatus()` | `getAndroidTtsStatus()` | `initializing`, `ready`, `error` を返す |
| `window.Android.getAndroidTtsVoices()` | `getAndroidTtsVoices()` | Android音声一覧JSONを返す |
| `window.Android.speakAndroidTts(...)` | `speakAndroidTts(...)` | 言語指定で再生 |
| `window.Android.speakAndroidTtsVoice(...)` | `speakAndroidTtsVoice(...)` | Android Voice名を指定して再生 |
| `window.Android.stopAndroidTts()` | `stopAndroidTts()` | Android TTS停止 |

Kotlin側の再生仕様:

- `TextToSpeech` 初期化成功で `androidTtsReady = true`, `androidTtsStatus = "ready"`。
- 音声一覧は `engine.voices` を `locale.toLanguageTag()` と `name` でソートして返す。
- 返却JSONには `source`, `name`, `voiceURI`, `lang`, `default`, `localService`, `networkRequired`, `quality`, `latency` を含む。
- 再生テキストは最大1000文字。
- 指定voiceがある場合は `engine.voice = requestedVoice`。
- 指定voiceがない場合は `engine.language` を設定する。
- 言語が未対応または言語データ不足なら `Locale.US` にフォールバックする。
- `Locale.US` も未対応ならfalseを返す。
- rateは0.1から3.0、pitchは0.1から2.0、volumeは0から1.0に丸める。
- `engine.stop()` 後、`TextToSpeech.QUEUE_FLUSH` で `engine.speak()` する。

### 停止処理

`TtsTest.stop(options)` は次を行います。

- `QuestionTtsLoop` を止める。ただし `skipQuestionLoop` がtrueなら止めない。
- Web Speechがあれば `speechSynthesis.cancel()`。
- Androidブリッジがあれば `window.Android.stopAndroidTts()`。
- 結果に応じてステータスを表示。

### 自動再生制限

手動再生はユーザー操作で起動します。自動再生はバトル出題時に設定ONなら呼び出されますが、Webブラウザ側の自動再生制限を受ける可能性があります。失敗時は `onError` でステータスを出し、繰り返し再生では次回スケジュール処理に進みます。

## 9. バトル画面での動作

通常バトルの問題文横に、`🔊` ボタンが表示されます。

- 要素ID: `q-tts-btn`
- 表示位置: `q-text` の横、`q-line` 内
- クリック時: `playCurrentQuestionTts()`

`updateQuestionTtsControl()` は `getCardSpeakableText(Game.cur)` が空でなければボタンを `inline-flex` 表示し、空なら非表示にします。TTS列がない従来カードでも、問題文 `q` から読み上げ対象を作るため、通常はボタンが表示されます。

### 手動再生

`playCurrentQuestionTts()` は次を行います。

1. 現在カードがなければ何もしない。
2. `QuestionTtsLoop.stop({ cancelSpeech: true })` で自動ループと現在再生を止める。
3. `TtsTest.speakCard(Game.cur)` を呼ぶ。

### 自動再生

`maybeAutoPlayQuestionTts(battleToken)` は次を満たす場合に自動再生します。

- `DB.d.ttsAutoEnabled` がtrue。
- `getCardSpeakableText(Game.cur)` が空でない。
- 120ms後にも同じバトル・同じカードで、HPが残っている。

繰り返しONの場合は `QuestionTtsLoop.start(card, battleToken, true)` になり、`repeatDelayMs = 500` で再生終了後に次回再生を予約します。選択肢を選ぶ、次の問題へ進む、バトル終了、ホームへ戻るなどで停止します。

### `tts_auto` との関係

`tts_auto` はデッキから `ttsAuto` として読み込まれ、オンライン対戦の問題セット同期などでは保持されます。ただし、現在の通常バトルの自動再生条件は `DB.d.ttsAutoEnabled` のみです。カード側 `ttsAuto` は `maybeAutoPlayQuestionTts()` の条件には使われていません。

### SRS・正解判定への影響

TTS再生は問題表示や回答判定のデータを変更しません。回答選択時には `QuestionTtsLoop.stop({ cancelSpeech: true })` が呼ばれますが、SRS、固定4択、正誤判定、習熟度更新のロジック自体には介入しません。

### オンライン対戦

オンライン対戦の問題セット作成・受信では、`ttsText`, `ttsLang`, `ttsAuto`, `exampleTts`, `exampleTtsLang` が存在する場合に問題セットへ含められます。これにより同期先でもTTS関連情報が失われないようになっています。

## 10. 問題確認・詳細画面での動作

詳細オーバーレイにはTTS表示欄があります。

| セクション | 要素ID | 表示条件 | 再生 |
|---|---|---|---|
| 問題TTS | `ov-tts-section` | `getCardSpeakableText(item)` が空でない | `問題音声` ボタンで `TtsTest.speakCard(item)` |
| 例文TTS | `ov-example-tts-section` | `getCardExampleTts(item)` が空でない | `例文音声` ボタンで `TtsTest.speakText(exampleText, exampleLang, ...)` |

問題TTS欄には、読み上げ対象テキストと `tts_lang` があれば `text / lang` の形で表示します。例文TTS欄には、`example_tts` と、`example_tts_lang` または問題側 `tts_lang` を表示します。

編集画面には現在、TTS専用フィールドはありません。問題文・解答・選択肢・解説などの編集はありますが、`tts_text` や `example_tts` を画面上で直接編集するUIは未実装です。

フルバックアップではカードデータごと保存されるためTTS列由来の内部プロパティも保持されます。学習記録のみエクスポートではデッキ本体を含めないため、カード別TTSテキストは出力対象ではありません。

## 11. 後方互換性

### 2列TSV / CSV

ヘッダーなし2列デッキは、1列目を問題、2列目以降を解答として読み込みます。TTS列がなくても読み込みエラーにはなりません。

CSV/TSVでヘッダーがある場合のみ、`tts_text` などのTTS列を列名で検出します。

### 従来の固定4択

固定4択列、`choices`、`correct` などの処理とは別にTTS列を読みます。固定4択データにTTS列がなくても問題ありません。TTS列がある場合はカードに `ttsText` などを付与します。

### Cloze

表形式インポートの1列目にClozeがある場合、`createClozeCards(q, parser, { ...ttsMetadata })` によりTTSメタデータが各Clozeカードへ適用されます。

通常TXTインポート内のClozeでは、TTS列ヘッダーを読む仕組みはありません。

### ZIP読み込み

ZIP内の `txt` / `csv` / `tsv` / `xlsx` / `ods` は `importDeckContentByName()` を通ります。CSV/TSV/XLSX/ODS内のTTS列は通常インポートと同じく読み込めます。TXT内ではTTS列としては扱われません。

### TTS列がないデッキ

TTS列がないカードでも、`getCardSpeakableText()` は問題文 `q` を正規化して読み上げ対象にします。したがってTTS列なしでも手動読み上げボタンは基本的に表示されます。

### 古いバックアップJSON

`ensureDataShape()` が `ttsAutoEnabled`, `ttsRepeatEnabled`, `ttsDefaultLang`, `ttsCleanupRules` を補完します。カード側も `normalizeCardTtsMetadata()` がsnake_caseとcamelCaseを吸収します。

### TTS設定がない状態

`ttsCleanupRules` がない場合は `createDefaultTtsCleanupRules()` に戻します。`ttsAutoEnabled` はfalse、`ttsRepeatEnabled` は自動再生OFFならfalse、`ttsDefaultLang` は空文字です。

## 12. Android向け注意点

Android実機では次の点に注意してください。

- 端末側で優先TTSエンジンを選ぶ必要があります。
- Google音声認識と音声合成サービスを使いたい場合は、Android設定側でそれを優先エンジンにします。
- MOZからAndroid本体のTTSエンジンは直接切り替えません。
- APK版のAndroid TTSは `TextToSpeech` の `voices` に出る音声だけを一覧化します。
- Web Speech API側は `speechSynthesis.getVoices()` に出る音声だけを一覧化します。
- 音声一覧が空の場合、端末のTTSエンジン、言語データ、WebView/ブラウザ対応状況を確認します。
- Android WebViewではWeb Speech APIが使えない、または音声一覧取得が遅れる場合があります。MOZはAndroid TTSブリッジと複数回の音声再取得で対策しています。
- Android TTSブリッジでは再生テキストが1000文字に制限されます。
- Android TTSブリッジでは、指定言語が未対応なら `Locale.US` にフォールバックします。

## 13. 他アプリへの移植手順

他アプリに移植する場合の最小手順です。

1. `speechSynthesis` と `SpeechSynthesisUtterance` の対応チェックを入れる。
2. `speechSynthesis.getVoices()` でWeb音声一覧を取得する。
3. `voiceschanged`、初期遅延再取得、手動再取得ボタンを実装する。
4. Android APK版が必要なら、`MainActivity.kt` 相当の `TextToSpeech` ブリッジを実装する。
5. Androidブリッジで `getAndroidTtsStatus`, `getAndroidTtsVoices`, `speakAndroidTts`, `speakAndroidTtsVoice`, `stopAndroidTts` を提供する。
6. Web音声とAndroid音声を同じ形に正規化し、`source: "web" | "android"` を付ける。
7. 言語カテゴリとスロットを用意する。
   - `free`
   - `en`
   - `ja`
   - `zh`
   - `default`
8. 選択音声をlocalStorageへ保存・復元する。
9. アプリDBへ自動再生設定、繰り返し設定、既定言語、除外ルールを保存する。
10. `speakText(text, lang, options)` を共通入口にする。
11. Web Speech再生関数とAndroid TTS再生関数を分ける。
12. `stopTts()` でWeb SpeechとAndroid TTSの両方を止める。
13. デッキ読み込みで `tts_text`, `tts_lang`, `tts_auto`, `example_tts`, `example_tts_lang` を任意列として読む。
14. `getCardSpeakableText(card)` を実装し、`tts_text` 優先、なければ問題文正規化にする。
15. 問題文横に手動再生ボタンを置く。
16. 自動再生ON/OFFと繰り返し再生ON/OFFを学習画面に接続する。
17. 詳細画面に問題音声・例文音声の手動再生ボタンを置く。
18. Web Speech非対応、Android TTS未初期化、音声一覧空、再生失敗時の表示を用意する。

移植時に最も重要なのは、再生処理そのものよりも「音声一覧が空でも壊れない」「Android TTSとWeb Speechを同じ音声リストとして扱う」「選択音声は端末依存なのでlocalStorageに保存する」という3点です。

## 14. 関連関数一覧

| 関数名・オブジェクト | 役割 | 移植時の重要度 |
|---|---|---|
| `TtsTest` | TTS設定、音声一覧、再生処理の中心オブジェクト | 必須 |
| `TtsTest.init()` | UIバインド、音声初期取得、`voiceschanged` 登録 | 必須 |
| `TtsTest.refreshVoices()` | Web/Android音声を再取得して描画 | 必須 |
| `TtsTest.normalizeWebVoice()` | Web Speech音声を共通形式に変換 | 必須 |
| `TtsTest.normalizeAndroidVoice()` | Android音声を共通形式に変換 | Android移植では必須 |
| `TtsTest.readAndroidVoices()` | `window.Android.getAndroidTtsVoices()` を読む | Android移植では必須 |
| `TtsTest.render()` | TTS設定画面の対応状況・音声一覧を更新 | 推奨 |
| `TtsTest.renderLanguageCategorySelect()` | 言語カテゴリselectを作る | 推奨 |
| `TtsTest.renderVoiceSelect(slot)` | スロット別の音声selectを作る | 必須 |
| `TtsTest.getVoiceKey()` | select値用の音声キーを作る | 必須 |
| `TtsTest.serializeVoice()` | localStorage保存用に音声をシリアライズ | 必須 |
| `TtsTest.readSavedVoice()` | 保存音声を読む | 必須 |
| `TtsTest.matchesSavedVoice()` | 保存音声と現在音声を照合 | 必須 |
| `TtsTest.saveSelectedVoice()` | 音声選択をlocalStorageへ保存 | 必須 |
| `TtsTest.findBestVoice()` | 言語に合う音声を選ぶ | 必須 |
| `TtsTest.resolveVoice()` | 実際に使う音声を決める | 必須 |
| `TtsTest.speakText()` | Web/Androidを切り替える共通再生入口 | 必須 |
| `TtsTest.speakCard()` | カードから読み上げ対象と言語を決めて再生 | 必須 |
| `TtsTest.speakWithWebSpeech()` | `SpeechSynthesisUtterance` で再生 | 必須 |
| `TtsTest.speakWithAndroid()` | Android TTSブリッジで再生 | Android移植では必須 |
| `TtsTest.stop()` | Web/Android TTS停止 | 必須 |
| `TtsTest.getSlotForLang()` | `en`, `ja`, `zh`, `default` へ分類 | 必須 |
| `TtsTest.getDefaultCardLang()` | 言語未指定カードの既定言語取得 | 推奨 |
| `QuestionTtsLoop` | バトル中の自動・繰り返し再生管理 | 学習画面移植では必須 |
| `QuestionTtsLoop.start()` | 自動再生開始、繰り返し予約 | 学習画面移植では必須 |
| `QuestionTtsLoop.stop()` | 自動再生停止 | 学習画面移植では必須 |
| `playCurrentQuestionTts()` | 問題文横ボタンの手動再生 | 必須 |
| `maybeAutoPlayQuestionTts()` | 出題時自動再生 | 推奨 |
| `updateQuestionTtsControl()` | 問題文横ボタンの表示制御 | 必須 |
| `getCardTtsText()` | `ttsText` / `tts_text` 取得 | 必須 |
| `getCardTtsLang()` | `ttsLang` / `tts_lang` 取得 | 必須 |
| `getCardTtsAuto()` | `ttsAuto` / `tts_auto` 取得 | 任意。現在の自動再生条件には未使用 |
| `getCardExampleTts()` | `exampleTts` / `example_tts` 取得 | 詳細画面移植では推奨 |
| `getCardExampleTtsLang()` | `exampleTtsLang` / `example_tts_lang` 取得 | 詳細画面移植では推奨 |
| `getCardSpeakableText()` | 読み上げ対象の決定 | 必須 |
| `normalizeQuestionTextForTts()` | 問題文から余計な読み上げ要素を除外 | 推奨 |
| `stripLeadingQuestionNumberForTts()` | 先頭番号除外 | 推奨 |
| `stripBracketedAnnotationsForTts()` | 括弧注記除外 | 推奨 |
| `stripTrailingPronunciationGuideForTts()` | 発音記号除外 | 推奨 |
| `stripTrailingPinyinForTts()` | ピンイン除外 | 推奨 |
| `stripDecorativeMarksForTts()` | 装飾記号除外 | 推奨 |
| `createDefaultTtsCleanupRules()` | 除外ルール既定値 | 推奨 |
| `normalizeTtsCleanupRules()` | 除外ルール補正 | 推奨 |
| `DB.getTtsColumnMap()` | 表形式デッキのTTS列検出 | デッキ移植では必須 |
| `DB.getTtsMetadataFromRow()` | 行からTTSメタデータ取得 | デッキ移植では必須 |
| `DB.applyTtsMetadataToCard()` | カードへTTSメタデータ付与 | デッキ移植では必須 |
| `DB.normalizeCardTtsMetadata()` | 旧形式・snake_case補正 | 後方互換には推奨 |
| `updateOverlayTts()` | 詳細画面の問題音声・例文音声表示 | 詳細画面移植では推奨 |
| `WebAppInterface.getAndroidTtsStatus()` | Android TTS状態取得 | Android移植では必須 |
| `WebAppInterface.getAndroidTtsVoices()` | Android TTS音声一覧取得 | Android移植では必須 |
| `WebAppInterface.speakAndroidTts()` | Android TTS言語指定再生 | Android移植では必須 |
| `WebAppInterface.speakAndroidTtsVoice()` | Android TTS音声名指定再生 | Android移植では推奨 |
| `WebAppInterface.stopAndroidTts()` | Android TTS停止 | Android移植では必須 |
| `MainActivity.initAndroidTts()` | Android `TextToSpeech` 初期化 | Android移植では必須 |
| `MainActivity.getAndroidTtsVoicesJson()` | Android音声一覧JSON生成 | Android移植では必須 |
| `MainActivity.speakWithAndroidTts()` | Android TTS再生本体 | Android移植では必須 |

## 15. 制約・未実装事項

現時点の制約は次の通りです。

- MP3ファイル再生やMP3生成には対応していません。
- 音声ファイルのIndexedDB保存には対応していません。
- Anki `.apkg` の直接解析には対応していません。
- Android本体の優先TTSエンジン切り替えはアプリ側からできません。
- Web Speech API非対応かつAndroidブリッジがない環境では読み上げできません。
- 音声品質、利用可能言語、音声数は端末・ブラウザ・TTSエンジン依存です。
- 自動再生はブラウザのユーザー操作制限を受ける可能性があります。
- rate / pitch / volume はUI上で変更できますが、現在はDBやlocalStorageに保存されません。
- 選択した具体的な音声はlocalStorage保存のみで、エクスポートJSONには含まれません。
- カード側 `tts_auto` は読み込まれ保持されますが、現在の通常バトル自動再生条件には使われていません。
- 編集画面で `tts_text`, `tts_lang`, `example_tts` などを直接編集するUIはありません。
- 通常TXT形式ではTTS列ヘッダーを解釈しません。TTS列を使う場合はCSV / TSV / XLSX / ODSなどの表形式が必要です。
- Android TTSブリッジでは読み上げテキストが最大1000文字に切り詰められます。
