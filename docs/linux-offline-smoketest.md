# Linux スモークテスト手順

## 1. 目的

フル件数を PostgreSQL へ投入する前に、RPS のテーブル一覧の先頭 `5` 件だけを別テーブルへ入れて、接続と登録処理を確認します。

このスモークテストでは以下を固定します。

- 取得件数: `5`
- result 出力先: `result-smoketest/`
- PostgreSQL 登録先: `public.rps_table_inventory_smoketest`

## 2. 前提

- 通常実行用の `config/rps-sync.properties` が作成済み
- Linux から Windows 11 VDI の PostgreSQL へ接続できる
- `scripts/linux/build.sh` 実行済み、または `dist/rps-table-sync.jar` が存在する

## 3. 実行

既定の 5 件で実行:

```bash
./scripts/linux/run-rps-sync-smoketest.sh
```

件数を変えて試したい場合:

```bash
./scripts/linux/run-rps-sync-smoketest.sh ./config/rps-sync.properties 3
```

## 4. 実行時に見るポイント

標準出力で以下を確認します。

- `maxTables=5`
- `postgresTarget=public.rps_table_inventory_smoketest`
- `tables.selected=5`
- `tables.saved=5`
- `tables.failed=0`

`tables.failed` が `1` 以上なら、その時点でフル投入へ進まないでください。

## 5. ファイル出力の確認

Linux 側で以下を確認します。

```bash
ls -1 result-smoketest/details | wc -l
```

期待値:

- `5`

サマリ確認:

```bash
sed -n '1,10p' result-smoketest/table_inventory.tsv
```

先頭 1 行はヘッダ、その後に 5 件のテーブル情報が入ります。

## 6. PostgreSQL 側の確認方法

Windows 11 VDI の PowerShell で以下を実行します。

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

Linux 側 TSV と一致確認:

```bash
cut -f3,4 result-smoketest/table_inventory.tsv
```

上の TSV の `table_name` と `version` が PostgreSQL 側の結果と一致すれば、最小テストは通っています。

## 7. 再実行前の初期化

同じ 5 件を繰り返し試す前に、テストテーブルを空にしたい場合だけ以下を実行します。

```powershell
& "$env:LOCALAPPDATA\PostgreSQL\17.9\app\bin\psql.exe" `
  -h 127.0.0.1 -p 5432 -U postgres -d postgres `
  -c "TRUNCATE TABLE public.rps_table_inventory_smoketest;"
```

## 8. 次の段階

このスモークテストで以下が確認できたら、通常実行へ進めます。

- 5 件の詳細取得が成功する
- `result-smoketest/details` に 5 ファイルできる
- `public.rps_table_inventory_smoketest` に 5 件入る
- Linux 側 TSV と PostgreSQL 側のテーブル名が一致する
