#!/usr/bin/env bash
set -euo pipefail
task_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$task_root"
if [[ -d /usr/lib/jvm/java-8-openjdk-amd64 ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
  export PATH="$JAVA_HOME/bin:$PATH"
fi
mvn -B -pl migration,verification -am package \
  -Dtest=PartScanQueueTest,ScanIteratorLifecycleTest,ScanIteratorPaginationTest,ValueCodecTest,MigrationBundleTest,FetchCollectorTest,NativeValueCodecTest,IdentifiersTest,OfflineComparatorTest,PlanBundleTest,VerifierCliTest \
  -Dsurefire.failIfNoSpecifiedTests=false
mkdir -p verification/target/fixture-oracle
javac -source 8 -target 8 -encoding UTF-8 \
  -cp verification/target/nebula-data-verifier-3.8.4.jar \
  -d verification/target/fixture-oracle task/acceptance/FixtureSourceOracle.java
