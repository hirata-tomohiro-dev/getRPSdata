# getRPSdata

社内オフライン環境向けに、RPS のテーブル一覧を取得し、社内に構築した PostgreSQL へ格納する Java ツールを追加しています。

主な同梱物:

- `src/main/java/jp/co/nksol/rpssync/RpsTableSyncApp.java`
- `dist/rps-table-sync.jar`
- `lib/postgresql-42.7.10.jar`
- `assets/java/linux-x64/OpenJDK17U-jre_x64_linux_hotspot_17.0.18_8.tar.gz`
- `scripts/linux/run-rps-sync.sh`
- `scripts/linux/run-rps-sync-smoketest.sh`
- `docs/linux-offline-runbook.md`
- `docs/linux-offline-smoketest.md`

今回追加した主要バイナリは合計約 `47.7MB` で、社内持ち込み制約の `500MB` 内です。

ツールの動き:

1. ログインして `authenticity_token` を取得
2. `set_schema` に対象スキーマを POST
3. テーブル一覧ページを取得
4. テーブル詳細ページを 0.1 秒間隔で巡回
5. `result/details/*.txt` と `result/table_inventory.tsv` を出力
6. `public.rps_table_inventory` に UPSERT

実行手順は [docs/linux-offline-runbook.md](/Users/hirata_tomohiro/src/work/getRPSdata/getRPSdata/docs/linux-offline-runbook.md) を参照してください。

いきなり全件を PostgreSQL に入れないための先頭 5 件スモークテストは [docs/linux-offline-smoketest.md](/Users/hirata_tomohiro/src/work/getRPSdata/getRPSdata/docs/linux-offline-smoketest.md) を参照してください。
