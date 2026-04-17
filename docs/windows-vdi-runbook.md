# Windows VDI 実行手順

## 1. 目的

このリポジトリは、Windows 11 VDI 上で Java ツールを実行し、同じ VDI 上の PostgreSQL へ RPS のテーブル一覧を保存するための資材です。

この構成では Linux は使いません。Java 実行も PostgreSQL 登録も VDI 内で完結します。

## 2. 同梱物

- `dist/rps-table-sync.jar`
- `lib/postgresql-42.7.10.jar`
- `assets/java/windows-x64/OpenJDK17U-jre_x64_windows_hotspot_17.0.18_8.zip`
- `scripts/windows/Setup-BundledJavaRuntime.ps1`
- `scripts/windows/Run-RpsSync.ps1`
- `scripts/windows/Run-RpsSyncSmokeTest.ps1`
- `config/rps-sync.properties.example`

主要バイナリ合計は約 `44.9MB` です。社内持ち込み制約の `500MB` 内に収まります。

## 3. 事前準備

1. このリポジトリを VDI に展開します。
2. PowerShell を開き、リポジトリ直下へ移動します。
3. スクリプトの実行を一時的に許可します。

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
Unblock-File -Path .\scripts\windows\*.ps1
```

4. 設定ファイルを作成します。

```powershell
Copy-Item .\config\rps-sync.properties.example .\config\rps-sync.properties
```

## 4. PostgreSQL の前提確認

この構成は VDI ローカル接続なので、Apache での公開や外部向けポート開放は不要です。

まず PostgreSQL が VDI 内で起動できることを確認してください。

```powershell
& "$env:LOCALAPPDATA\PostgreSQL\17.9\app\bin\psql.exe" `
  -h 127.0.0.1 -p 5432 -U postgres -d postgres `
  -c "SELECT version();"
```

もし `postgresql.conf` や `pg_hba.conf` が見つからない場合は、まだ `initdb` 前の可能性があります。先に `postgreSQL` リポジトリ側で初期導入を完了してください。

`postgreSQL` リポジトリのルートで実行:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\windows\Install-PostgreSQLOffline.ps1
```

この VDI ローカル実行構成では、`listen_addresses='127.0.0.1'` のままで構いません。

## 5. 設定値

`config\rps-sync.properties` で最低限見直す項目:

- `rps.loginUrl`
- `rps.baseUrl`
- `rps.tableListUrl`
- `rps.schema`
- `rps.proxyHost`
- `rps.proxyPort`
- `pg.password`

PostgreSQL 接続先は既定で VDI ローカルです。

```properties
pg.host=127.0.0.1
pg.port=5432
pg.database=postgres
pg.user=postgres
pg.table=rps_table_inventory
```

`rps.proxyHost` は、VDI から RPS へ直接アクセスできるなら空のままで構いません。プロキシが必要な場合だけ設定してください。

## 6. 実行

通常実行:

```powershell
.\scripts\windows\Run-RpsSync.ps1
```

別の設定ファイルを使う場合:

```powershell
.\scripts\windows\Run-RpsSync.ps1 -ConfigPath .\config\rps-sync.properties
```

## 7. 実行結果

成功すると以下が生成されます。

- `result\debug\_debug_login.html`
- `result\debug\_debug_set_schema.html`
- `result\debug\_debug_table_list.html`
- `result\details\*.txt`
- `result\table_inventory.tsv`
- `result\failed_tables.tsv` (失敗時のみ)

PostgreSQL 側には `public.rps_table_inventory` が自動作成され、以下の主キーで UPSERT されます。

- `source_schema`
- `instance_name`
- `table_name`
- `version`

## 8. 実行後の確認

件数確認:

```powershell
& "$env:LOCALAPPDATA\PostgreSQL\17.9\app\bin\psql.exe" `
  -h 127.0.0.1 -p 5432 -U postgres -d postgres `
  -c "SELECT COUNT(*) FROM public.rps_table_inventory;"
```

内容確認:

```powershell
& "$env:LOCALAPPDATA\PostgreSQL\17.9\app\bin\psql.exe" `
  -h 127.0.0.1 -p 5432 -U postgres -d postgres `
  -c "SELECT source_schema, instance_name, table_name, version, synced_at FROM public.rps_table_inventory ORDER BY table_name LIMIT 20;"
```

## 9. 補足

- `rps.sleepMillis` の既定値は `100` です
- `sync.maxTables=0` は全件、`sync.maxTables=5` は先頭 5 件だけです
- 実パスワードはコミットしないでください
- `pg.password` を設定ファイルに書きたくない場合は、PowerShell 実行前に `setx PG_PASSWORD "<password>"` または一時的に `$env:PG_PASSWORD="<password>"` でも渡せます
