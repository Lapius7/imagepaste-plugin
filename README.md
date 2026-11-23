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

引数なしの `/imagepaste` でヘルプが開きます。ヘルプの行はクリックで入力欄に入ります。

| コマンド | 内容 |
|---|---|
| `/imagepaste <URL> [key=value ...]` | プレビューを開始 (別名 `/ip`)。URL は `://` を含むもの |
| `help` | ヘルプ |
| `size <w> [h]` | プレビューの大きさを指定 |
| `rotate [0\|90\|180\|270]` | 回転 (省略で90度ずつ) |
| `flip [none\|h\|v\|both]` | 反転 (省略で順に切替) |
| `confirm` / `cancel` | 確定 / 中止 |
| `undo` | 直前の貼り付けを削除 |
| `list [all] [ページ]` | 一覧。行のボタンから削除・移動できます (`all` は管理者のみ) |
| `remove <id>` | 指定IDを削除 (他人のものは管理者のみ) |
| `tp <id>` | 貼り付けの場所へ移動 |
| `reload` | config.yml と lang.yml を再読み込み (管理者) |

## プレビュー中の操作

右クリック=確定 / 左クリック=中止 / Shift+スクロール=拡縮 / F=回転 / Q=反転。
操作一覧は画面上部のボスバーに出ます (`preview.guide.mode` で bossbar / scoreboard / chat / none)。現在の大きさ・向き・置けるかどうかはアクションバーに出ます。

- 見る先が遠くても (`preview.max-distance`、既定64) 置けます。確定は、そのとき表示している位置で行います。空を向いたままの右クリックでも確定できます。
- 素手のままだと空中の右クリックがサーバーに届かないため、持ち手が空のときだけ一時的に紙を持たせます (`preview.air-click-item: false` で無効)。終了時に回収します。

## 保護と整合

貼った額縁は、`protection.enabled` が true の間、次の操作を拒否します: 破壊 (プレイヤー・爆発・水流など)、回転、アイテムの出し入れ、地図の取り出し。ドロップは出ません。
`protection.allow-break-permission` が true なら、`imagepaste.break` を持つ人だけ壊せます。

額縁が失われたとき (`/kill`、他プラグイン、チャンクの再読み込みで見つからないなど) は、`protection.on-break` に従って記録を整えます。

| 値 | 動作 |
|---|---|
| `protect` | 欠けた枠の記録と地図データだけ外す (全部なくなれば貼り付けごと消す) |
| `remove-all` | 貼り付け全体を撤去する |
| `regenerate` | 欠けた額縁を同じ場所・向きで作り直す (置けなければ記録を外す) |

起動時に、記録にない地図データと、記録にない額縁を整理します。

## 演出

プレビューの枠と、設置時の演出は別の設定です。

- **プレビューの枠**: `preview.particle` `particle-size` `edge-step` `*-color` など。控えめな単線の枠です。
- **設置演出** (`effects.place`): 確定した瞬間に、貼り付け範囲の面に沿って粒子を散らします。外周から中心へ波のように収束し、四隅でバーストします。`duration-ticks` かけて段階的に出て、効果音も時間差で鳴ります。
  - `layers`: 面に散らす粒子の一覧 (種類・タイルごとの数・速さ)。既定は END_ROD / FIREWORK / GLOW / ENCHANT / TOTEM_OF_UNDYING
  - `corner-bursts`: 四隅で弾ける粒子
  - `sounds`: 効果音の一覧 (音・音量・高さ・開始 tick)
  - `count-multiplier`: 粒子数の倍率 (0 で粒子なし)、`enabled: false` で演出ごと無効
- **撤去演出** (`effects.remove`): 粒子1種と音1つ。

## 確定用の紙と持ち物

- プレビュー開始時に、空きホットバーへ確定用の紙を入れて選びます。空きがなければ今のスロットの品を `plugins/ImagePaste/stash/<UUID>.yml` に退避します。
- 終了・キャンセル・切断・死亡・サーバー停止で元の品とスロットに戻ります。落ちた場合も、次のログインか起動時に復元します。死亡時 (keepInventory なし) は退避中の品を落とし物に加えます。
- 紙は移動・ドロップ・コンテナへの投入ができません。別の物を持っていても右クリックで確定できます。

## 設定ファイルの更新

`config.yml` は `config-version` で管理します。起動と reload のとき、旧版の既定値のままの上限 (16 / 16 / 128) は新しい既定値 (0 = 無制限) に更新し、変更済みの値は残し、足りないキーは補います。更新前は `config.yml.bak-v<旧版>` に控えます。

## 大きな画像・タイル数

- `limits.max-width` `max-height` `max-tiles` と `download.max-source-pixels` は 0 以下で無制限です (既定は無制限)。内部の安全上限は 262144 タイルです。
- 人ごとの上限は権限で付けます: `imagepaste.max-tiles.<数>` `imagepaste.max-size.<数>` (持っている中で最大のもの。config の上限より大きくはなりません)。`imagepaste.bypass` は上限なしです。
- 額縁と地図は `place.tiles-per-tick` ずつ複数tickに分けて作り、進捗をアクションバーに出します。`/imagepaste cancel` で中止すると置いた分を片付けます。
- 画像の変換は非同期で、1行ずつ処理します (大きな画像でも作業用メモリは数行分)。
- 未ロードのチャンクはロードしながら置きます。`place.load-chunks: false` にすると読み込み済みの範囲だけになります。
- 保存: 各タイルのピクセルは `plugins/ImagePaste/` に自前で保存し、描画時に読みます。ただし Bukkit の仕様で地図1枚ごとに `map_N.dat` も作られるため、数千枚規模ではワールドの容量が増えます。

## 権限

| 権限 | 内容 | 既定 |
|---|---|---|
| `imagepaste.use` | 貼り付け・undo・自分の削除 | op |
| `imagepaste.admin` | reload、他人の一覧・削除 | op |
| `imagepaste.break` | 保護された額縁を壊す | op |
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

### lang.yml (文言)

メッセージは `plugins/ImagePaste/lang.yml` に分けてあります。MiniMessage 形式で、色・クリック・ホバーも書けます。`<id>` のような部分は実行時の値です。足りない項目は内蔵の既定文を使います。`/imagepaste reload` で反映されます。

## セキュリティ

- http / https のみ。認証情報つきURLは拒否します。
- 内部アドレス (ループバック、プライベート、リンクローカルなど) は、リダイレクト先も含めて毎回拒否します。
- サイズ・画素数・時間に上限があります。

## ビルド

```
gradle build   # build/libs/mc-image-paste-<version>.jar
```
