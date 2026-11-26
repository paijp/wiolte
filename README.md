# wiolte

Wio LTE（STM32F439 + EC21）が、SORACOM経由で **自分自身のファームウェアを更新する** ための評価用一式です。
Claude などが VPS 上のファイルを置き換えるだけで、ファームの更新、ログの取得、コマンドの送信ができることを目標にしています。

```
Claude ── VPS: data/<ID>/firmware.bin を置く / cmd.txt に追記 / log.txt を読む
               ↑↓ HTTPS
         SORACOM Beam ウェブサイトエントリポイント（http://beam.soracom.io:18080 → https://VPS）
               ↑↓ LTE
         Wio LTE: [bootloader] + [アプリ + WioOTA]
```

## 実機で確認済み

- `rel/wiolte-full.bin` をブラウザ（WebUSB、Android の Chrome）から DFU で書き込み、LTE 接続・サーバー接続まで動作
- VPS に `firmware.bin`（約 126KB）を置いてから、ダウンロード・書き込み・新ファームでの起動まで約 1 分
- `cmd.txt` のコマンド（`ping`、`led 0 0 10`）が次のポーリングで届き、応答が `log.txt` に返る

## 構成

| ディレクトリ | 内容 |
|---|---|
| `bootloader/` | ブートローダー（約650バイト、セクタ0–2）。更新記録があれば、一時保存領域の内容をアプリ領域へコピーしてから起動します |
| `libraries/WioOTA/` | Arduinoライブラリ。ログ送信、コマンド受信、ファーム更新の検出とダウンロードを行います |
| `libraries/WioOTA/examples/OtaBasic/` | サンプルスケッチ |
| `server/index.php` | サーバー側（Beamの転送先）。PHPのファイル1つです |
| `web/` | Wio LTE に USB DFU で `.bin` を書き込み、USB シリアルを表示するページ（WebUSB）。GitHub Pages で公開 |

## フラッシュ配置

| セクタ | アドレス | サイズ | 用途 |
|---|---|---|---|
| 0–2 | 0x08000000 | 48KB | ブートローダー |
| 3 | 0x0800C000 | 16KB | 更新記録（magic / len / crc / done / fail） |
| 4–7 | 0x08010000 | 448KB | アプリ |
| 8–11 | 0x08080000 | 512KB | 一時保存領域（ダウンロードしたイメージ） |

Wio LTE の STM32F439VI は 2MB ですが、Seeed のボード設定に合わせて前半 1MB だけを使っています（後半は未使用）。

アプリの開始アドレス 0x08010000 は、Seeed STM32F4 コアの `USER_ADDR_ROM` と同じです。
そのため、コアを改造せずにビルド時のプロパティ指定だけで、ブートローダーの後ろに配置できます。

## 更新の流れ

1. アプリが `Ota.poll()` を定期的に呼びます（標準は15秒間隔）。
   - 溜まったログを `POST ?a=log` で送ります。
   - `GET ?a=poll&crc=<現在のCRC>` で、コマンドと更新の有無を受け取ります。応答は必ず `ok` で始まり、それ以外（Beam のエラーページなど）は異常として扱います。
2. サーバーの `firmware.bin` のCRCが端末のCRCと違うと、応答に `fw <len> <crc>` が入ります。
3. WioOTAは16KBずつ `GET ?a=fw` で取得し、一時保存領域に書き込みます。全体のCRCを確認してから更新記録を書き、リセットします。
4. ブートローダーは一時保存領域のCRCを再確認し、アプリ領域へコピー・検証してから `done` を記録し、アプリを起動します。
   - コピー中に電源が落ちても、更新記録は「未完了」のまま残ります。次の起動でコピーをやり直します。
5. 新しいアプリは、更新記録にあるCRCを報告します。サーバー側のCRCと一致するので、同じファームを再度受け取ることはありません。

## ビルド

### ブートローダー

```sh
make -C bootloader          # bootloader/bootloader.bin
```

### スケッチ（arduino-cli）

```sh
arduino-cli compile --fqbn SeeedJP:Seeed_STM32F4:wioGpsM4 \
  --libraries libraries \
  --build-property build.ldscript=ld/flash.ld \
  --build-property build.vect=VECT_TAB_FLASH \
  --output-dir out libraries/WioOTA/examples/OtaBasic
# out/OtaBasic.ino.bin がサーバーに置くイメージ（0x08010000 用）
```

GitHub Actions（`.github/workflows/build.yml`）が、ブートローダー、サンプルスケッチ（OtaBasic、OtaDiag）をビルドします。main への push では、ビルド結果を `rel/` にコミットします（開発中の最新版）。`v*` のタグを push すると、GitHub Releases に一式（ファーム、`WioOTA-x.y.z.zip`、オフライン用 `wiolte-dfu.html`、サーバー一式）を添付したリリースを作ります。タグは `library.properties` の `version` と一致させてください。

| ファイル | 内容 |
|---|---|
| `rel/wiolte-full.bin` | ブートローダー + 最小アプリ（新品のボードに DFU で書く 1 ファイル） |
| `rel/bootloader.bin` | ブートローダーのみ |
| `rel/OtaBasic.ino.bin` | サンプルアプリ（0x08010000 用。サーバーに置くイメージの例） |


## 初回の書き込み（USB DFU）

BOOTボタンを押しながらRSTを押して離すと、DFUモードになります。

新品のボードには `rel/wiolte-full.bin` を1つ書くだけで済みます。ブートローダー（0x08000000）と、最初のダウンロードを行う最小アプリ（OtaBasic、0x08010000）を1ファイルにまとめたものです。書き込み後はLTEに接続してサーバーを確認し、`firmware.bin` があればそれに置き換わります。

- ブラウザから書き込む場合（Chrome / Edge、WebUSB）: https://paijp.github.io/wiolte/ を開き、「wiolte-full.bin を使う」→ **program** を押して、一覧から DFU デバイスを選びます。書き込み後はスケッチの USB シリアルを表示します（Android は WebUSB、デスクトップは Web Serial）。ページは `web/`（`index.html` + `app.js`）で、`web/wiolte-dfu.html` はオフライン用の 1 ファイル版です（`python3 web/make-offline.py` で再生成）。Windows では DFU デバイスに WinUSB ドライバーが、Linux では udev の設定が必要です。
- PCから書き込む場合:
  ```sh
  dfu-util -d 0483:df11 -a 0 -s 0x08000000:leave -D rel/wiolte-full.bin
  ```

以後の更新は、サーバーに `firmware.bin` を置くだけです。

## サーバー設定

1. `server/index.php` を VPS に置きます。PHPの書き込み権限が必要な `data/` ディレクトリが、同じ場所に作られます。
2. SORACOM Beam で **ウェブサイトエントリポイント** を設定します（HTTPエントリポイントはクエリを転送しないため使えません）。
   - 転送先: `https://<VPS>/<設置パス>/`（端末は `http://beam.soracom.io:18080/?a=...` にアクセスし、クエリごと転送されます。サーバー側の実装やパスを変えてもファームの修正は不要です）
   - IMSI ヘッダーまたは IMEI ヘッダーの付与を有効にします（署名ヘッダーは任意）。サーバーは `X-Soracom-IMSI`、なければ `X-Soracom-IMEI` を端末 ID（`data/<ID>/`）に使います。
3. （任意）署名ヘッダーを使う場合は、事前共有鍵を `server/config.php` に設定します。
   ```php
   <?php $config["beam_secret"] = "（Beamに設定した事前共有鍵）";
   ```
4. スケッチ側のURLは `OTA_URL`（初期値 `http://beam.soracom.io:18080/`）です。
5. nginx では `data/` と `config.php` を外部から読めないようにしてください。PHP は `Content-Length` を付けて応答します（Wio LTE は chunked 転送を扱えないため）。

### 操作（VPS 上）

```sh
cp out/OtaBasic.ino.bin data/<ID>/firmware.bin   # ファーム更新
echo "led 0 0 10" >> data/<ID>/cmd.txt           # コマンド送信（次のポーリングで1回だけ届く）
echo "reset"      >> data/<ID>/cmd.txt           # リセット（WioOTA の組み込みコマンド）
echo "interval 1" >> data/<ID>/cmd.txt           # ポーリング間隔を 1 秒に（組み込み。再起動で既定に戻る）
tail -f data/<ID>/log.txt                         # ログ
```

## シリアルログをサーバーに集める（web/ のアップロードモード）

`web/` を `serial.php` と同じサーバー（例: `https://<VPS>/wiolte/web/`）に置き、`#t=<トークン>` 付きで開くと、ページのログ（ボードの USB シリアル出力とページ自身の行）を 2 秒ごとに `serial.php` へ送ります。サーバーでは `data/serial/<トークン>.log` に追記されます。トークンなしで開いたとき（GitHub Pages など）は何も送りません。

```sh
./wiolte-serial new 24     # 24 時間有効なトークンを作り、ページの URL を表示
./wiolte-serial tail       # 最新のログの末尾
./wiolte-serial list       # トークンの一覧
```

## USB シリアルでの切り分け

サンプルと WioOTA は、次のような内容を USB シリアルに出します（Web ページで表示できます）。サーバーに届かない問題の切り分けに使えます。

| 出力 | 意味 |
|---|---|
| `lte: power on` → `lte: attach soracom.io` → `lte: connected, server ...` | LTE 接続の各段階（失敗時は理由付きでリトライ） |
| `wioota: boot (...)` | 前回のリセットでブートローダーが何をしたか（DFU で書かれた／新ファームをインストール／CRC 不一致で拒否） |
| `wioota: poll GET failed (no HTTP response; ...)` | HTTP 応答なし（LTE や Beam を確認） |
| `wioota: log POST HTTP 404` など | サーバーが HTTP エラーを返した |
| `wioota: unexpected poll reply (...)` | `ok` で始まらない応答（Beam の転送先の誤りなど） |
| `wioota: server OK` | サーバーと通信できた |

## アプリ側の約束

- `Ota.poll()` を `loop()` から頻繁に呼んでください。通信は、設定した間隔（`Ota.begin()` の第3引数、既定とサンプルは 15 秒）ごとにだけ行います。サーバーから `interval <秒>` コマンドで変更できます（開発中は `interval 1`、終わったら `interval 15` など）。間隔は通信量に直結します。
- サーバーに置くファームにも必ず WioOTA を入れてください。入っていないファームを入れると、以後の遠隔更新ができず、USB DFU での書き直しが必要になります。
- ログは `Ota.print()` / `Ota.println()` で書きます。4KBのバッファに溜まり、次のポーリングで送られます。
- コマンドは `Ota.onCommand(handler)` で、1行ずつ受け取ります。

## 制限（評価版）

- アプリが暴走して `Ota.poll()` が呼ばれなくなると、遠隔では復旧できません（USB DFUが必要です）。
  対策として、IWDG、起動確認とロールバック、ブートローダー自体の通信機能などは今後の課題です。
- サーバーに `firmware.bin` があると、DFUで別のアプリを書いても、次のポーリングでサーバー側のファームに置き換わります。DFUで書いたアプリを使い続けたいときは、`firmware.bin` を消してください。
- 通信中（数秒）は `poll()` が戻りません。
- `firmware.bin` の正当性は、BeamとHTTPSによる経路の保護と、CRCによる転送エラーの検出に頼っています。署名検証はしていません。

## ライセンス

MIT（`LICENSE`）。
