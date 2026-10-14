# wiolte

Wio LTE（STM32F439 + EC21）が、SORACOM経由で **自分自身のファームウェアを更新する** ための評価用一式です。
Claude などが VPS 上のファイルを置き換えるだけで、ファームの更新、ログの取得、コマンドの送信ができることを目標にしています。

```
Claude ── VPS: data/<IMSI>/firmware.bin を置く / cmd.txt に追記 / log.txt を読む
               ↑↓ HTTPS
         SORACOM Beam ウェブサイトエントリポイント（http://beam.soracom.io:18080 → https://VPS）
               ↑↓ LTE
         Wio LTE: [bootloader] + [アプリ + WioOTA]
```

## 構成

| ディレクトリ | 内容 |
|---|---|
| `bootloader/` | ブートローダー（約650バイト、セクタ0–2）。更新記録があれば、一時保存領域の内容をアプリ領域へコピーしてから起動します |
| `libraries/WioOTA/` | Arduinoライブラリ。ログ送信、コマンド受信、ファーム更新の検出とダウンロードを行います |
| `libraries/WioOTA/examples/OtaBasic/` | サンプルスケッチ |
| `server/index.php` | サーバー側（Beamの転送先）。PHPのファイル1つです |
| `android/` | Wio LTEにUSB DFUで `.bin` を書き込むAndroidアプリ（OTG接続） |

## フラッシュ配置（1MB）

| セクタ | アドレス | サイズ | 用途 |
|---|---|---|---|
| 0–2 | 0x08000000 | 48KB | ブートローダー |
| 3 | 0x0800C000 | 16KB | 更新記録（magic / len / crc / done / fail） |
| 4–7 | 0x08010000 | 448KB | アプリ |
| 8–11 | 0x08080000 | 512KB | 一時保存領域（ダウンロードしたイメージ） |

アプリの開始アドレス 0x08010000 は、Seeed STM32F4 コアの `USER_ADDR_ROM` と同じです。
そのため、コアを改造せずにビルド時のプロパティ指定だけで、ブートローダーの後ろに配置できます。

## 更新の流れ

1. アプリが `Ota.poll()` を定期的に呼びます（標準は10秒間隔）。
   - 溜まったログを `POST ?a=log` で送ります。
   - `GET ?a=poll&crc=<現在のCRC>` で、コマンドと更新の有無を受け取ります。
2. サーバーの `firmware.bin` のCRCが端末のCRCと違うと、応答に `fw <len> <crc>` が入ります。
3. WioOTAは2KBずつ `GET ?a=fw` で取得し、一時保存領域に書き込みます。全体のCRCを確認してから更新記録を書き、リセットします。
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

GitHub Actions（`.github/workflows/build.yml`）が、ブートローダー、サンプルスケッチ、Android APKをビルドします。main への push では、ビルド結果を `rel/` にコミットします（`rel/bootloader.bin`、`rel/OtaBasic.ino.bin`、`rel/wioflash.apk`）。
APKは、リポジトリにある固定のデバッグ鍵（`android/debug.keystore`）で署名します。そのため、新しいAPKを上書きインストールできます。

## 初回の書き込み（USB DFU）

BOOTボタンを押しながらRSTを押して離すと、DFUモードになります。

- PCから書き込む場合:
  ```sh
  dfu-util -d 0483:df11 -a 0 -s 0x08000000 -D bootloader/bootloader.bin
  dfu-util -d 0483:df11 -a 0 -s 0x08010000:leave -D out/OtaBasic.ino.bin
  ```
- Androidから書き込む場合: `android/` のアプリ（Wio Flash）で `.bin` を選び、`Boot`（0x08000000）または `App`（0x08010000）を選んで書き込みます。

以後の更新は、サーバーに `firmware.bin` を置くだけです。

## サーバー設定

1. `server/index.php` を VPS に置きます。PHPの書き込み権限が必要な `data/` ディレクトリが、同じ場所に作られます。
2. SORACOM Beam で **ウェブサイトエントリポイント** を設定します（HTTPエントリポイントはクエリを転送しないため使えません）。
   - 転送先: `https://<VPS>`（端末の `/wiolte/index.php?...` がパスとクエリごと転送されます）
   - IMSIヘッダー付与を有効にします（署名ヘッダーは任意）。
3. （任意）署名ヘッダーを使う場合は、事前共有鍵を `server/config.php` に設定します。
   ```php
   <?php $config["beam_secret"] = "（Beamに設定した事前共有鍵）";
   ```
4. スケッチ側のURLは `OTA_URL`（初期値 `http://beam.soracom.io:18080/wiolte/index.php`）です。
5. nginx では `data/` と `config.php` を外部から読めないようにしてください。PHP は `Content-Length` を付けて応答します（Wio LTE は chunked 転送を扱えないため）。

### 操作（VPS 上）

```sh
cp out/OtaBasic.ino.bin data/<IMSI>/firmware.bin   # ファーム更新
echo "led 0 0 10" >> data/<IMSI>/cmd.txt           # コマンド送信（次のポーリングで1回だけ届く）
echo "reset"      >> data/<IMSI>/cmd.txt           # リセット（WioOTA の組み込みコマンド）
tail -f data/<IMSI>/log.txt                         # ログ
```

## アプリ側の約束

- `Ota.poll()` を `loop()` から頻繁に呼んでください。通信は、設定した間隔ごとにだけ行います。
- ログは `Ota.print()` / `Ota.println()` で書きます。4KBのバッファに溜まり、次のポーリングで送られます。
- コマンドは `Ota.onCommand(handler)` で、1行ずつ受け取ります。

## 制限（評価版）

- アプリが暴走して `Ota.poll()` が呼ばれなくなると、遠隔では復旧できません（USB DFUが必要です）。
  対策として、IWDG、起動確認とロールバック、ブートローダー自体の通信機能などは今後の課題です。
- サーバーに `firmware.bin` があると、DFUで別のアプリを書いても、次のポーリングでサーバー側のファームに置き換わります。DFUで書いたアプリを使い続けたいときは、`firmware.bin` を消してください。
- 通信中（数秒）は `poll()` が戻りません。
- `firmware.bin` の正当性は、BeamとHTTPSによる経路の保護と、CRCによる転送エラーの検出に頼っています。署名検証はしていません。
