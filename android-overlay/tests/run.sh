#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .deps build/fixture-classes
if [ ! -f .deps/json.jar ]; then
  curl -fsSL --max-time 60 https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar -o .deps/json.jar
fi
java com.sun.tools.javac.Main -cp .deps/json.jar -d build/fixture-classes app/src/main/java/com/panclub/shareprobe/SharePipeline.java app/src/main/java/com/panclub/shareprobe/PinIdentityResolver.java app/src/main/java/com/panclub/shareprobe/PublicPinData.java app/src/main/java/com/panclub/shareprobe/SourceCardHandoff.java app/src/main/java/com/panclub/shareprobe/TargetPanContract.java app/src/main/java/com/panclub/shareprobe/SourcePanRecovery.java app/src/main/java/com/panclub/shareprobe/ConsumerResultPresenter.java app/src/main/java/com/panclub/shareprobe/PreBakeSession.java app/src/main/java/com/panclub/shareprobe/ApprovedPreviewFixture.java app/src/main/java/com/panclub/shareprobe/DiagnosticEasyVeganLauncher.java app/src/main/java/com/panclub/shareprobe/PreviewEligibilityGate.java app/src/main/java/com/panclub/shareprobe/PreviewAuthorityGate.java app/src/main/java/com/panclub/shareprobe/PreviewResultPresenter.java app/src/main/java/com/panclub/shareprobe/EasyVeganPreviewSession.java app/src/main/java/com/panclub/shareprobe/FitEligibilityGate.java app/src/main/java/com/panclub/shareprobe/GeometryFactorStage.java app/src/main/java/com/panclub/shareprobe/BatterMassSimulator.java app/src/main/java/com/panclub/shareprobe/AndroidIntegrationStage.java tests/PipelineTest.java tests/PinIdentityResolverTest.java tests/LiveEvidenceTest.java tests/SourceCardHandoffTest.java tests/TargetPanContractTest.java tests/PreBakeSessionTest.java tests/ConsumerResultPresenterTest.java tests/EasyVeganPreviewSessionTest.java tests/GeneralizedPreviewPipelineTest.java tests/DiagnosticEasyVeganLauncherTest.java tests/FitEligibilityGateTest.java tests/GeometryFactorStageTest.java tests/BatterMassSimulatorTest.java tests/AndroidIntegrationStageTest.java
java -cp build/fixture-classes:.deps/json.jar com.panclub.shareprobe.PipelineTest
java -cp build/fixture-classes:.deps/json.jar com.panclub.shareprobe.PinIdentityResolverTest
java -cp build/fixture-classes:.deps/json.jar com.panclub.shareprobe.LiveEvidenceTest
java -cp build/fixture-classes:.deps/json.jar com.panclub.shareprobe.SourceCardHandoffTest
java -cp build/fixture-classes:.deps/json.jar com.panclub.shareprobe.TargetPanContractTest
java -cp build/fixture-classes:.deps/json.jar com.panclub.shareprobe.PreBakeSessionTest
java -cp build/fixture-classes:.deps/json.jar com.panclub.shareprobe.ConsumerResultPresenterTest
java -cp build/fixture-classes:.deps/json.jar com.panclub.shareprobe.EasyVeganPreviewSessionTest
java -cp build/fixture-classes:.deps/json.jar com.panclub.shareprobe.GeneralizedPreviewPipelineTest
java -cp build/fixture-classes:.deps/json.jar com.panclub.shareprobe.DiagnosticEasyVeganLauncherTest
java -cp build/fixture-classes:.deps/json.jar com.panclub.shareprobe.FitEligibilityGateTest
java -cp build/fixture-classes:.deps/json.jar com.panclub.shareprobe.GeometryFactorStageTest
java -cp build/fixture-classes:.deps/json.jar com.panclub.shareprobe.BatterMassSimulatorTest
java -cp build/fixture-classes:.deps/json.jar com.panclub.shareprobe.AndroidIntegrationStageTest
PYTHONPATH=probe python3 -m unittest discover -s probe -p 'test_*.py'
