# mc-image-paste

画像のURLを、地図入りの額縁タイルとして壁・床・天井に貼る Paper プラグインです。
対象は Paper 26.3 / Java 25 です。

## 使い方

1. `/imagepaste <画像URL> [オプション]` を実行します。画像を取得すると **プレビューモード** に入ります。
2. 貼りたい面を見ると、貼り付け範囲が粒子の枠で表示されます。外周は水色、四隅は白、**上辺は黄色の線と矢印**で、画像の上がどちらかを示します。置けない枠があると全体が赤になります。
3. 操作は次のとおりです。アクションバーに大きさ・向き・反転・タイル数が出ます。

| 操作 | 内容 |
|---|---|
| Shift + スクロール | 大きさを拡縮 |
| `F` (持ち替え) | 90度回転 (時計回り) |
| `Q` (ドロップ) | 反転を切替 (なし→左右→上下→両方) |
| 右クリック | 確定して貼る |
| 左クリック | キャンセル |

4. 床では、画像の上はプレイヤーが向いている方向、天井では逆向きです。壁では上が上になります。
5. `/imagepaste undo` で直前の貼り付けを消せます。再起動後も残ります。

例: `/imagepaste https://example.com/a.png width=6 quality=high background=#000000`

## コマンド

| コマンド | 内容 |
|---|---|
| `/imagepaste <URL> [key=value ...]` | プレビューを開始 (別名 `/ip`) |
| `size <w> [h]` | プレビューの大きさを指定 |
| `rotate [0\|90\|180\|270]` | 回転 (省略で90度ずつ) |
| `flip [none\|h\|v\|both]` | 反転 (省略で順に切替) |
| `confirm` | 見ている面に確定 |
| `undo` | 直前の貼り付けを削除 |
| `cancel` | プレビューを中止 |
| `list [all]` | 自分の貼り付け一覧 (`all` は管理者のみ) |
| `remove <id>` | 指定IDを削除 (他人のものは管理者のみ) |
| `reload` | config.yml を再読み込み (管理者) |

## 権限

| 権限 | 内容 | 既定 |
|---|---|---|
| `imagepaste.use` | 貼り付け・undo・自分の削除 | op |
| `imagepaste.admin` | reload、他人の一覧・削除 | op |
| `imagepaste.bypass` | クールダウンとサイズ上限を無視 | なし |

## 設定一覧

`config.yml` の `defaults` が既定値です。コマンドの `key=value` で、その回だけ上書きできます。

| 項目 (コマンド名) | 値 | 内容 |
|---|---|---|
| `width` (`w`) | 整数 | 横のブロック数 |
| `height` (`h`) | 整数 / 0 | 縦のブロック数。0 は縦横比から自動 |
| `mode` | fit / fill / stretch | 全体を収める / 隙間なく覆う / 引き伸ばす |
| `quality` | low / normal / high | `resample` と `dither` の一括指定 |
| `resample` | nearest / bilinear / bicubic | 拡大縮小の方式 |
| `dither` | none / floyd / ordered | ディザリング |
| `brightness` | -1.0〜1.0 | 明るさ |
| `contrast` | 0.0〜3.0 | コントラスト |
| `saturation` | 0.0〜3.0 | 彩度 |
| `background` | transparent / #RRGGBB | 余白と透明部分の色 |
| `rotate` | 0 / 90 / 180 / 270 | 時計回りの回転 (壁・床・天井) |
| `flip` | none / h / v / both | 反転 (左右 / 上下 / 両方) |
| `invisible` (config は `invisible-frame`) | true/false | 額縁を不可視にする |
| `glow` (config は `glow-frame`) | true/false | 発光額縁を使う |
| `fixed` | true/false | 額縁を固定する (回転・取り外し不可) |
| `blocked` (config は `on-blocked`) | abort / skip | 置けない枠があるとき全体を中止 / その枠だけ飛ばす |

### limits (制限)

| 項目 | 既定 | 内容 |
|---|---|---|
| `max-width` / `max-height` | 16 | 最大ブロック数 |
| `max-tiles` | 128 | 1回の最大タイル(地図)数 |
| `cooldown-seconds` | 10 | 連続実行の間隔 |
| `pending-timeout-seconds` | 120 | 操作がないままプレビューを保てる時間 |

### preview (プレビュー)

| 項目 | 既定 | 内容 |
|---|---|---|
| `interval-ticks` | 4 | 枠の更新間隔 (tick) |
| `max-distance` | 10 | 見ている面を探す距離 |
| `particle` | DUST | 粒子の種類 (DUST 以外は色が効かない) |
| `particle-size` | 1.0 | DUST の大きさ |
| `edge-step` | 0.5 | 粒子の間隔 (ブロック) |
| `edge-color` / `top-color` / `corner-color` / `error-color` | #55FFFF / #FFFF55 / #FFFFFF / #FF5555 | 外周 / 上辺 / 四隅 / エラー時の色 |
| `scroll-sensitivity` | 1 | 何ノッチで1段階変えるか |
| `scroll-step` | 1 | 1段階で変わるブロック数 |
| `invert-scroll` | false | スクロール方向を逆にする |
| `actionbar-enabled` / `actionbar` | true | アクションバー表示と書式 |

### download (取得)

| 項目 | 既定 | 内容 |
|---|---|---|
| `max-bytes` | 10MiB | ファイルサイズ上限 |
| `max-source-pixels` | 40,000,000 | 元画像の画素数上限 |
| `connect-timeout-seconds` / `timeout-seconds` | 5 / 15 | 接続・全体の時間上限 |
| `max-redirects` | 3 | リダイレクト回数上限 |
| `max-concurrent` | 3 | 同時取得数 |
| `allowed-ports` | [80, 443] | 許可ポート |
| `allowed-hosts` | [] | 空なら全公開ホスト。指定すると完全一致かサブドメインのみ |

### messages (文言)

`messages` 以下で変更できます。`&` で色を指定でき、`{cols}` `{rows}` `{count}` `{n}` `{s}` `{reason}` `{id}` などが使えます。

## セキュリティ

- http / https のみ。認証情報つきURLは拒否します。
- 内部アドレス (ループバック、プライベート、リンクローカルなど) は、リダイレクト先も含めて毎回拒否します。
- サイズ・画素数・時間に上限があります。

## ビルド

```
gradle build   # build/libs/mc-image-paste-<version>.jar
```
