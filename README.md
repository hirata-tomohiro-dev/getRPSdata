# getRPSdata

社内オフライン環境向けに、Windows 11 VDI 上で RPS のテーブル一覧を取得し、同じ VDI 上の PostgreSQL へ格納する Java ツールです。

主な同梱物:

- `src/main/java/jp/co/nksol/rpssync/RpsTableSyncApp.java`
- `dist/rps-table-sync.jar`
- `lib/postgresql-42.7.10.jar`
- `assets/java/windows-x64/OpenJDK17U-jre_x64_windows_hotspot_17.0.18_8.zip`
- `scripts/windows/Run-RpsSync.ps1`
- `scripts/windows/Run-RpsSyncSmokeTest.ps1`
- `docs/windows-vdi-runbook.md`
- `docs/windows-vdi-smoketest.md`

今回の主要バイナリは合計約 `44.9MB` で、社内持ち込み制約の `500MB` 内です。

ツールの動き:

1. ログインして `authenticity_token` を取得
2. `set_schema` に対象スキーマを POST
3. テーブル一覧ページを取得
4. テーブル詳細ページを 0.1 秒間隔で巡回
5. `result/details/*.txt` と `result/table_inventory.tsv` を出力
6. VDI ローカルの `public.rps_table_inventory` に UPSERT

実行手順は [docs/windows-vdi-runbook.md](/Users/hirata_tomohiro/src/work/getRPSdata/getRPSdata/docs/windows-vdi-runbook.md) を参照してください。

いきなり全件を PostgreSQL に入れないための先頭 5 件スモークテストは [docs/windows-vdi-smoketest.md](/Users/hirata_tomohiro/src/work/getRPSdata/getRPSdata/docs/windows-vdi-smoketest.md) を参照してください。
