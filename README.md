# ImagePaste

![Minecraft](https://img.shields.io/badge/Minecraft-1.18.2%20~%2026.3-62b47a)
![Paper](https://img.shields.io/badge/Paper-%E5%BF%85%E9%A0%88-0a6cff)
![Java](https://img.shields.io/badge/Java-17%2B-orange)
[![Release](https://img.shields.io/github/v/release/Lapius7/imagepaste-plugin)](https://github.com/Lapius7/imagepaste-plugin/releases/latest)

画像の URL を指定すると、地図入りの額縁タイルとして壁・床・天井に貼れる Paper プラグインです。
貼る前にパーティクルの枠でプレビューでき、大きさ・向き・反転をその場で調整できます。

## 特徴

- URL から画像を取得し、地図の色に減色して貼り付けます(PNG / JPEG / GIF など、Java が読める形式)。
- プレビュー付き。見ている面に貼り付け範囲が枠で出ます。上辺は黄色で、画像の上がどちらかが分かります。
- Shift+スクロールで拡縮、`F` で回転、`Q` で反転。右クリックで確定、左クリックで中止します。
- 大きな画像は複数 tick に分けて配置し、進捗をボスバーに表示します。`/imagepaste cancel` で中止して片付けられます。
- 貼ったものは記録され、`list` / `undo` / `remove` / `tp` で管理できます。再起動後も残ります。
- 貼った額縁は保護され、破壊・回転・地図の取り出しを拒否します。
- 取得先の制限(内部アドレスの拒否、サイズ・時間の上限、ホストの許可リスト)があります。
- 文言は `lang.yml` (MiniMessage) で変えられます。

## 対応バージョン

1 つの jar で、Paper 1.18.2 〜 26.3 を対象にしています。Java 17 でコンパイルしているので、サーバー側は各 Minecraft 版が要求する Java で動きます(1.18〜1.20.4 は 17 以上、1.20.5 以降は 21 以上、26.x は 25)。

| 版 | Paper | ロード・起動 | 貼り付け〜undo |
|---|---|---|---|
| 1.18.2 | build 388 | 確認済み | 確認済み |
| 1.19.4 | 最新 build | 確認済み | 確認済み |
| 1.20.1 | 最新 build | 確認済み | 確認済み |
| 1.20.4 | 最新 build | 確認済み | 確認済み |
| 1.20.6 | 最新 build | 確認済み | 確認済み |
| 1.21.1 | 最新 build | 確認済み | 確認済み |
| 1.21.4 | 最新 build | 確認済み | 確認済み |
| 1.21.11 | 最新 build | 確認済み | 確認済み |
| 26.1.2 | 最新 build | 確認済み | 未検証 |
| 26.2 | 最新 build | 確認済み | 未検証 |
| 26.3 | build 259 | 確認済み | 未検証 |
| 1.17 以前 | - | 未検証 (対象外) | - |

- 「ロード・起動」は、その版の Paper サーバーに jar を入れて起動し、`Enabling ImagePaste` と読み込み完了のログが出て、エラーがないことを見ています。
- 「貼り付け〜undo」は、ヘッドレスのボット(mineflayer)で入室し、`/imagepaste <URL> width=2` → 壁を見て右クリック → 額縁 4 個の出現 → `list` → `undo` で額縁が 0 個に戻るところまでを見ています。26.x は、ボット側がまだ対応しておらず、この手順を回せていません。
- 未検証の範囲: 実プレイヤーのクライアントでの見た目(プレビュー粒子、ボスバー、サイドバー)、Shift+スクロール・`F`・`Q` の操作、複数人での同時利用、Folia。

**Spigot / CraftBukkit では動きません。** メッセージに Paper 同梱の Adventure (MiniMessage) を使っているためです。Paper 必須です。

## インストール

1. [Releases](https://github.com/Lapius7/imagepaste-plugin/releases/latest) から `ImagePaste-<版>.jar` をダウンロードします。
2. サーバーの `plugins/` に入れて起動します。
3. `plugins/ImagePaste/config.yml` と `lang.yml` が作られます。権限を付けるか、OP で `/imagepaste` を実行してください。

## 使い方

1. `/imagepaste <画像URL> [オプション]` を実行します。画像を取得すると**プレビュー**に入ります。
2. 貼りたい面を見ます。外周は水色、四隅は白、上辺は黄色の線と矢印です。置けない枠があると全体が赤になります。
3. 次の操作で調整し、右クリックで貼ります。

| 操作 | 内容 |
|---|---|
| Shift + スクロール | 大きさを拡縮 |
| `F` (持ち替え) | 90 度回転 (時計回り) |
| `Q` (ドロップ) | 反転を切替 (なし → 左右 → 上下 → 両方) |
| 右クリック | 確定して貼る |
| 左クリック | キャンセル |

- 床では画像の上はプレイヤーが向いている方向、天井では逆向きです。壁では上が上になります。
- 狙っている面が遠くても (`preview.max-distance`、既定 64) 置けます。確定は、そのとき表示している位置で行います。
- 素手だと空中の右クリックがサーバーに届かないため、持ち手が空のときだけ一時的に紙を持たせます(`preview.air-click-item: false` で無効)。終了時に元の持ち物へ戻します。落ちた場合も、次のログインか起動時に復元します。
- 操作の一覧は `preview.guide.mode` で bossbar / scoreboard / chat / none に切り替えられます。

例: `/imagepaste https://example.com/a.png width=6 quality=high background=#000000`

## コマンド

引数なしの `/imagepaste` (別名 `/ip`) でヘルプが開きます。ヘルプの行はクリックで入力欄に入ります。

| コマンド | 内容 |
|---|---|
| `/imagepaste <URL> [key=value ...]` | プレビューを開始。URL は `://` を含むもの |
| `help` | ヘルプ |
| `size <w> [h]` | プレビューの大きさを指定 |
| `rotate [0\|90\|180\|270]` | 回転 (省略で 90 度ずつ) |
| `flip [none\|h\|v\|both]` | 反転 (省略で順に切替) |
| `confirm` / `cancel` | 確定 / 中止 (処理中の取り消しにも使います) |
| `undo` | 直前の貼り付けを削除 |
| `list [all] [ページ]` | 一覧。`all` は管理者のみ |
| `remove <id>` | 指定 ID を削除 (他人のものは管理者のみ) |
| `tp <id>` | 貼り付けの場所へ移動 |
| `reload` | config.yml と lang.yml を再読み込み (管理者) |

## 権限

| 権限 | 内容 | 既定 |
|---|---|---|
| `imagepaste.use` | 貼り付け・undo・自分の削除 | op |
| `imagepaste.admin` | reload、他人の一覧・削除 | op |
| `imagepaste.break` | 保護された額縁を壊す | op |
| `imagepaste.bypass` | クールダウンとサイズ上限を無視 | なし |
| `imagepaste.max-tiles.<数>` | その人のタイル数上限 (持っている中で最大のもの) | なし |
| `imagepaste.max-size.<数>` | その人の縦横の上限 | なし |

## 設定

`config.yml` の `defaults` が既定値で、コマンドの `key=value` でその回だけ上書きできます。

| 項目 (コマンドでの名前) | 値 | 内容 |
|---|---|---|
| `width` (`w`) | 整数 | 横のブロック数 (既定 4) |
| `height` (`h`) | 整数 / 0 | 縦のブロック数。0 は縦横比から自動 |
| `mode` | fit / fill / stretch | 全体を収める / 隙間なく覆う / 引き伸ばす |
| `quality` | low / normal / high | `resample` と `dither` の一括指定 |
| `resample` | nearest / bilinear / bicubic | 拡大縮小の方式 |
| `dither` | none / floyd / ordered | ディザリング |
| `brightness` | -1.0〜1.0 | 明るさ |
| `contrast` | 0.0〜3.0 | コントラスト |
| `saturation` | 0.0〜3.0 | 彩度 |
| `background` | transparent / #RRGGBB | 余白と透明部分の色 |
| `rotate` | 0 / 90 / 180 / 270 | 時計回りの回転 |
| `flip` | none / h / v / both | 反転 |
| `invisible` (config は `invisible-frame`) | true / false | 額縁を不可視にする |
| `glow` (config は `glow-frame`) | true / false | 発光額縁を使う |
| `fixed` | true / false | 額縁を固定する |
| `blocked` (config は `on-blocked`) | abort / skip | 置けない枠があるとき、全体を中止 / その枠だけ飛ばす |

### limits

| 項目 | 既定 | 内容 |
|---|---|---|
| `max-width` / `max-height` | 0 | 最大ブロック数。0 以下は無制限 |
| `max-tiles` | 0 | 1 回の最大タイル(地図)数。0 は無制限 (内部の安全上限は 262144) |
| `cooldown-seconds` | 10 | 連続実行の間隔 |
| `pending-timeout-seconds` | 120 | 操作がないままプレビューを保てる時間 |

### download

| 項目 | 既定 | 内容 |
|---|---|---|
| `max-bytes` | 10485760 | ファイルサイズ上限 (10 MiB) |
| `max-source-pixels` | 40000000 | 元画像の画素数上限。0 以下で無制限 |
| `connect-timeout-seconds` / `timeout-seconds` | 5 / 15 | 接続・全体の時間上限 |
| `max-redirects` | 3 | リダイレクト回数上限 |
| `max-concurrent` | 3 | 同時取得数 |
| `allowed-ports` | [80, 443] | 許可ポート |
| `allowed-hosts` | [] | 空なら全公開ホスト。指定すると完全一致かサブドメインのみ |

### place

| 項目 | 既定 | 内容 |
|---|---|---|
| `tiles-per-tick` | 16 | 1 tick に作るタイル数。0 で一度に全部 |
| `load-chunks` | true | false なら読み込み済みチャンクの範囲だけに置く |

### preview

| 項目 | 既定 | 内容 |
|---|---|---|
| `interval-ticks` | 4 | 枠の更新間隔。小さいほど滑らかだが負荷が増える |
| `max-distance` | 64 | 見ている面を探す距離 (ブロック) |
| `air-click-item` | true | 素手のとき空中クリック用の紙を持たせる |
| `particle` | DUST | 粒子の種類。DUST 以外は色指定が効かない |
| `particle-size` | 1.0 | DUST の大きさ |
| `edge-step` | 0.5 | 粒子の間隔 (ブロック) |
| `edge-color` / `top-color` / `corner-color` / `error-color` | #55FFFF / #FFFF55 / #FFFFFF / #FF5555 | 外周 / 上辺 / 四隅 / エラー時の色 |
| `scroll-sensitivity` / `scroll-step` | 1 / 1 | 何ノッチで 1 段階か / 1 段階で変わるブロック数 |
| `invert-scroll` | false | スクロール方向を逆にする |
| `actionbar-enabled` | true | 現在値をアクションバーに出す |
| `guide.mode` | bossbar | 操作一覧の表示: bossbar / scoreboard / chat / none |
| `guide.bossbar-color` / `guide.bossbar-overlay` | BLUE / PROGRESS | ボスバーの色と形 |

### effects

| 項目 | 内容 |
|---|---|
| `place.*` | 設置演出。`enabled`、`duration-ticks`、`count-multiplier`、`layers` (粒子)、`corner-bursts`、`sounds` |
| `progress.*` | 処理中の進捗表示。`display` は bossbar / actionbar / title / none。`show-eta`、`frame.*` (範囲の枠の点滅) |
| `remove.*` | 撤去演出。粒子 1 種と音 1 つ |

粒子名は、新しい名前 (`DUST`、`EXPLOSION_EMITTER`) と古い名前 (`REDSTONE`、`EXPLOSION_HUGE`) のどちらでも指定できます。その版にない粒子は無視されます。

### protection

| 項目 | 既定 | 内容 |
|---|---|---|
| `enabled` | true | 破壊・回転・アイテムの出し入れ・地図の取り出しを拒否 |
| `allow-break-permission` | true | `imagepaste.break` を持つ人だけ壊せる |
| `on-break` | protect | 額縁が失われたとき: `protect` は記録だけ外す / `remove-all` は全体を撤去 / `regenerate` は作り直す |

メッセージは `plugins/ImagePaste/lang.yml` にあり、MiniMessage 形式です。`<id>` のような部分は実行時の値で、足りない項目は内蔵の文を使います。`/imagepaste reload` で反映されます。
config.yml は `config-version` で管理していて、旧版の既定値のままの項目は新しい既定値に更新し、変更済みの値は残します。更新前は `config.yml.bak-v<旧版>` に控えます。

## よくある質問

**Spigot で使えますか。**
使えません。Paper が必要です。

**画像が取得できません。**
`http` / `https` のみで、ポートは 80 / 443 が既定です。ループバックやプライベートアドレスはリダイレクト先も含めて拒否します。ファイルが 10 MiB を超える場合は `download.max-bytes` を上げてください。

**プレビューの枠が見えません。**
`preview.max-distance` の範囲内のブロックを見ているか確認してください。クライアントの粒子設定が「最小」だと、表示されないことがあります。

**色が元の画像と違います。**
地図は決まったパレット (数十色) しか使えないので、減色されます。`quality=high` やディザリング (`dither=floyd`) で見た目が近くなります。

**貼った画像を消すには。**
`/imagepaste undo` か `/imagepaste remove <id>` です。額縁を直接壊しても保護されるので消えません。壊せる権限 (`imagepaste.break`) を付けると、壊した分の記録が整理されます。

**古い版で粒子が出ません。**
一部の粒子は新しい版にしかありません。`effects.place.layers` などで、その版にある粒子を指定してください。

## 制限事項

- 無制限タイルは、サーバーの負荷に直結します。既定は上限なしですが、公開サーバーでは `limits.max-tiles` か `imagepaste.max-tiles.<数>` で上限を設けてください。地図 1 枚ごとに `map_N.dat` ができるので、数千枚規模ではワールドの容量も増えます。
- 配置は `place.tiles-per-tick` ずつ進むので、大きな画像は時間がかかります。
- 地図の色数の制限があるため、写真は元の色とは一致しません。
- 1.17 以前と Folia は対象外です。Folia は動作を確認していません。
- クライアント側の操作 (スクロール、`F`、`Q`、右クリック確定) は、実機での確認が自動化できていません。

## ビルド

Java 17 以上と Gradle が必要です。

```
gradle build
```

`build/libs/ImagePaste-<版>.jar` ができます。最小対応版 (Paper 1.18.2 の API) に対して Java 17 向けにコンパイルしており、新しい版にだけある API は実行時に調べて使います。
