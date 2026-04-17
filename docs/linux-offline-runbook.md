# Linux 実行手順

## 1. 目的

このリポジトリには、以下をまとめています。

- RPS のテーブル一覧と詳細 HTML を取得する Java ツール
- 取得結果を `result/` 配下へ保存する処理
- 取得したテーブル一覧を PostgreSQL へ UPSERT する処理
- Linux オフライン環境で動かすための同梱 Java ランタイム

## 2. 同梱物

- `dist/rps-table-sync.jar`
- `lib/postgresql-42.7.10.jar`
- `assets/java/linux-x64/OpenJDK17U-jre_x64_linux_hotspot_17.0.18_8.tar.gz`
- `scripts/linux/setup-bundled-runtime.sh`
- `scripts/linux/run-rps-sync.sh`
- `config/rps-sync.properties.example`

主要バイナリ合計は約 `47.7MB` です。今回追加した資材だけであれば、`500MB` の持ち込み制約内に収まります。

## 3. Linux 側の準備

1. リポジトリを展開します。
2. 設定ファイルを作ります。

```bash
cp config/rps-sync.properties.example config/rps-sync.properties
chmod 600 config/rps-sync.properties
```

3. `config/rps-sync.properties` の以下を実値へ置き換えます。

- `rps.loginUrl`
- `rps.baseUrl`
- `rps.tableListUrl`
- `rps.schema`
- `rps.proxyHost`
- `rps.proxyPort`
- `pg.host`
- `pg.port`
- `pg.database`
- `pg.user`
- `pg.password`

4. Linux から Windows 11 VDI の PostgreSQL へ TCP 接続できることを確認します。

## 4. Windows 11 VDI 側の PostgreSQL 受け口設定

このツールは Linux ホストから VDI 上 PostgreSQL へ接続します。`postgreSQL` リポジトリの既定設定はローカル接続向けなので、Linux ホスト `10.206.224.170` から受ける設定を追加してください。

既定配置先:

- PostgreSQL アプリ: `%LOCALAPPDATA%\PostgreSQL\17.9\app`
- データディレクトリ: `%LOCALAPPDATA%\PostgreSQL\17.9\data`

`postgresql.conf` に以下を設定:

```conf
listen_addresses = '*'
port = 5432
```

`pg_hba.conf` に以下を追加:

```conf
host    all    postgres    10.206.224.170/32    scram-sha-256
```

設定変更後に VDI 上で再起動:

```powershell
.\scripts\windows\Stop-PostgreSQLOffline.ps1
.\scripts\windows\Start-PostgreSQLOffline.ps1
```

## 5. 実行

Linux 側で以下を実行します。

```bash
./scripts/linux/run-rps-sync.sh
```

別の設定ファイルを使う場合:

```bash
./scripts/linux/run-rps-sync.sh /path/to/rps-sync.properties
```

## 6. 実行結果

成功すると以下が生成されます。

- `result/debug/_debug_login.html`
- `result/debug/_debug_set_schema.html`
- `result/debug/_debug_table_list.html`
- `result/details/*.txt`
- `result/table_inventory.tsv`
- `result/failed_tables.tsv` (失敗があった場合のみ)

PostgreSQL 側には `public.rps_table_inventory` が自動作成され、以下の主キーで UPSERT されます。

- `source_schema`
- `instance_name`
- `table_name`
- `version`

保持カラム:

- `description`
- `detail_url`
- `detail_header`
- `detail_table_html`
- `detail_text_file`
- `synced_at`

## 7. VDI 上での確認

PowerShell で確認:

```powershell
& "$env:LOCALAPPDATA\PostgreSQL\17.9\app\bin\psql.exe" `
  -h 127.0.0.1 -p 5432 -U postgres -d postgres `
  -c "SELECT source_schema, instance_name, table_name, version, synced_at FROM public.rps_table_inventory ORDER BY table_name LIMIT 20;"
```

件数確認:

```powershell
& "$env:LOCALAPPDATA\PostgreSQL\17.9\app\bin\psql.exe" `
  -h 127.0.0.1 -p 5432 -U postgres -d postgres `
  -c "SELECT COUNT(*) FROM public.rps_table_inventory;"
```

## 8. 補足

- `rps.sleepMillis` の既定値は `100` です。参考プログラムの要求どおり、リクエスト間隔を 0.1 秒にしています
- 実パスワードはコミットしないでください
- `pg.password` を設定ファイルに書きたくない場合は `PG_PASSWORD` 環境変数でも渡せます
