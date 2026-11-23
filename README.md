# mc-image-paste

画像のURLを、地図入りの額縁タイルとして壁・床・天井に貼る Paper プラグインです。
対象は Paper 26.3 / Java 25 です。

## 使い方

1. `/imagepaste <画像URL> [オプション]` を実行します。画像は非同期で取得・減色します。
2. 「準備完了」と出たら、貼りたいブロックの面を右クリックします。クリックしたブロックの面に接する位置が **左下** になり、右と上へ広がります。
3. 床・天井では、画像の上はプレイヤーが向いている方向になります。
4. `/imagepaste undo` で直前の貼り付けを消せます。再起動後も残ります。

例: `/imagepaste https://example.com/a.png width=6 quality=high background=#000000`

## コマンド

| コマンド | 内容 |
|---|---|
| `/imagepaste <URL> [key=value ...]` | 貼り付け待ちにする (別名 `/ip`) |
| `undo` | 直前の貼り付けを削除 |
| `cancel` | 貼り付け待ちを中止 |
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
| `rotate` | 0 / 90 / 180 / 270 | 床・天井の回転 (壁では無視) |
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
| `pending-timeout-seconds` | 60 | URL指定後、右クリックを待つ時間 |

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
