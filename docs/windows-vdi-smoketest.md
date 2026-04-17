# Windows VDI スモークテスト手順

## 1. 目的

いきなり全件を PostgreSQL に入れず、RPS のテーブル一覧の先頭 `5` 件だけを別テーブルへ保存して、最小限の接続確認をします。

このスモークテストでは以下を固定します。

- 取得件数: `5`
- 出力先: `result-smoketest\`
- PostgreSQL 登録先: `public.rps_table_inventory_smoketest`

## 2. 前提

- `config\rps-sync.properties` が作成済み
- VDI 上の PostgreSQL が起動済み
- `.\scripts\windows\Run-RpsSync.ps1` が動かせる状態

## 3. 実行

既定の 5 件で実行:

```powershell
.\scripts\windows\Run-RpsSyncSmokeTest.ps1
```

件数を変えて試したい場合:

```powershell
.\scripts\windows\Run-RpsSyncSmokeTest.ps1 -TableLimit 3
```

## 4. 実行時に見るポイント

標準出力で以下を確認します。

- `maxTables=5`
- `postgresTarget=public.rps_table_inventory_smoketest`
- `tables.selected=5`
- `tables.saved=5`
- `tables.failed=0`

`tables.failed` が `1` 以上なら、全件実行へ進まないでください。

## 5. ファイル出力の確認

PowerShell で件数確認:

```powershell
(Get-ChildItem .\result-smoketest\details\*.txt).Count
```

期待値:

- `5`

TSV 確認:

```powershell
Get-Content .\result-smoketest\table_inventory.tsv | Select-Object -First 10
```

## 6. PostgreSQL 側の確認

件数確認:

```powershell
& "$env:LOCALAPPDATA\PostgreSQL\17.9\app\bin\psql.exe" `
  -h 127.0.0.1 -p 5432 -U postgres -d postgres `
  -c "SELECT COUNT(*) AS smoke_count FROM public.rps_table_inventory_smoketest;"
```

期待値:

- `5`

登録テーブル確認:

```powershell
& "$env:LOCALAPPDATA\PostgreSQL\17.9\app\bin\psql.exe" `
  -h 127.0.0.1 -p 5432 -U postgres -d postgres `
  -c "SELECT table_name, version, synced_at FROM public.rps_table_inventory_smoketest ORDER BY synced_at, table_name;"
```

PowerShell 側 TSV との突合:

```powershell
Import-Csv -Delimiter "`t" .\result-smoketest\table_inventory.tsv |
  Select-Object table_name, version
```

ここで出る `table_name` と `version` が PostgreSQL 側の結果と一致すれば、最小テストは通っています。

## 7. 再実行前の初期化

同じ確認をやり直す前に、テストテーブルだけ空にしたい場合:

```powershell
& "$env:LOCALAPPDATA\PostgreSQL\17.9\app\bin\psql.exe" `
  -h 127.0.0.1 -p 5432 -U postgres -d postgres `
  -c "TRUNCATE TABLE public.rps_table_inventory_smoketest;"
```

## 8. 次の段階

次の 4 点が揃ったら通常実行へ進めます。

- `result-smoketest\details` に 5 ファイルある
- `result-smoketest\table_inventory.tsv` に 5 件ある
- `public.rps_table_inventory_smoketest` に 5 件ある
- TSV と PostgreSQL の `table_name` と `version` が一致する
