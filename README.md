# Java PDF Quiz Generator

텍스트 기반 한국어 PDF에서 용어·설명 관계를 추출해 O/X와 4지선다 퀴즈를 만드는 Java 데스크톱 프로그램입니다. Swing에서 생성 문항을 검토·편집하고, 직접 문제를 추가하거나 저장된 풀이 결과로 오답을 복습할 수 있습니다. 외부 AI API를 사용하지 않습니다.

GitHub 저장소 이름은 **`java-pdf-quiz-generator`**이며, 사용자가 선택한 공개 범위는 **Public**입니다. 공개용 소스 구성에서는 공유 권한이 확인되지 않은 강의 자료와 개인 데이터를 제외합니다.

## 주요 기능

- PDFBox 기반 텍스트 추출과 페이지·줄 출처 보존: 한 번에 최대 50페이지 범위를 선택합니다.
- O/X 및 4지선다 생성, 정답 위치 분배, seed를 주입한 생성 재현성 검증.
- 객관식 정방향/역방향/섞기 및 문항 수 선택.
- GUI 기본 추출 모드는 LEGACY입니다. STRUCTURED는 실험적 선택 옵션으로 원문 대조가 필요합니다.
- 검토 화면에서 편집·제외·직접 작성, 원문 페이지 보기 및 선택한 텍스트로 작성 시작.
- 문제 세트 이름·과목·생성일, 수정본/가져온 복사본 연결 정보, JSON 내보내기/가져오기.
- 첫 시도 답안 기준 채점·오답 복습·통합 오답노트.
- 기존 문제 세트 JSON v1 읽기와 v2 저장. 풀이 결과는 별도 JSON으로 저장합니다.

스캔 PDF OCR은 지원하지 않습니다. 자동 추출 결과가 항상 완전한 정의나 올바른 문제임을 보장하지 않으므로 저장 전 원문을 확인하세요.

## 실행 환경

- **JDK 25**: `pom.xml`의 source/target/release가 모두 25입니다.
- Maven 3.x와 인터넷 연결 또는 필요한 의존성이 있는 로컬 Maven 캐시.
- GUI 실행에는 그래픽 데스크톱 환경이 필요합니다. 제공한 실행 스크립트는 Windows PowerShell용입니다.
- 직접 의존성: Apache PDFBox 3.0.3, 테스트용 JUnit 4.11.

프로젝트 루트에서 환경을 확인합니다.

```powershell
java -version
mvn -version
```

## Swing GUI 실행

```powershell
cd C:\Quiz_Maker\quiz-maker
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-gui.ps1
```

다른 경로에 clone했다면 해당 프로젝트 폴더에서 실행하세요. 스크립트는 프로젝트 루트로 이동한 뒤 Maven package와 런타임 classpath 생성을 수행합니다. 이 실행 준비 단계는 테스트를 건너뛰므로, 전체 검증은 아래 명령을 별도로 실행해야 합니다.

GUI 사용 순서:

1. `PDF 선택` 후 페이지 범위·생성 옵션을 정하고 `문제 생성`을 누릅니다.
2. 문항을 검토하고 필요하면 편집·제외하거나 `문제 추가`로 직접 작성합니다.
3. `원문 보기`에서 출처를 대조합니다. 선택한 원문으로 문제를 작성할 수도 있습니다.
4. 세트를 저장하고 퀴즈를 시작합니다. 저장된 결과로 오답 복습과 오답노트를 이용합니다.

파일 선택기로 고른 PDF로 생성하면 같은 세션에서 원문 연결을 재사용합니다. 연결은 세트별 메모리에만 보관하며 최대 200개입니다. 앱을 재시작하면 사라집니다. 불러온 세트의 신뢰하지 않는 sourcePdf를 명시적 사용자 선택으로 간주하지 않습니다.

## 콘솔 실행

```powershell
mvn -q package '-DskipTests'
mvn -q dependency:build-classpath '-Dmdep.outputFile=target/runtime-classpath.txt' '-Dmdep.includeScope=runtime'
$quizDeps = (Get-Content target/runtime-classpath.txt -Raw -Encoding UTF8).Trim()
$quizRuntime = 'target\classes;' + $quizDeps
java -cp $quizRuntime com.quiz.Main
```

PDF 경로를 입력한 뒤 메뉴를 사용합니다. `load`로 저장 세트를, `review`로 풀이 결과와 오답 복습을 불러올 수 있습니다. `target/*.jar`는 의존성을 합친 실행 JAR가 아니므로 위 classpath 또는 GUI 스크립트를 사용하세요.

## 테스트 자료와 현재 업로드 범위

**원본 강의 PDF와 강의 원문을 포함한 기준 JSON·상세 분석 보고서는 공유 권한 확인 전 업로드에서 제외합니다.** `.gitignore`는 로컬 파일을 삭제하지 않고 Git 업로드 후보에서만 제외합니다. 사용자는 현재 이 자료들을 제외하기로 선택했습니다.

현재 소스 중심 검토안에는 Java 소스·테스트 소스, 합성 v1 JSON 테스트 리소스, 실행 스크립트와 일반 기술 문서가 포함됩니다. PDF가 없어도 프로그램을 빌드하고 사용자가 자신의 PDF로 실행할 수 있습니다.

```powershell
# 원본 테스트 자료가 없는 소스 checkout에서도 빌드 가능
mvn package '-DskipTests'
```

**전체 회귀 테스트는 다음 별도 자료가 정확한 경로와 원본 내용으로 있어야 합니다.** 자료가 없는 checkout에서 전체 테스트 통과를 주장하지 않습니다. 임의의 다른 PDF나 새로 캡처한 기준으로 대체하지 마세요.

| 구분 | 필요한 로컬 경로 |
| --- | --- |
| PDF | `sample.pdf` |
| PDF | `samples/a_behavior.pdf` |
| PDF | `samples/b_behavior.pdf.pdf` |
| PDF | `samples/c_behavior.pdf.pdf` |
| 기준 | `src/test/resources/baseline/mc-seed7-forward30-before.json` |
| 기준 | `src/test/resources/baseline/ox-seed7-before.json` |
| 기준 | `src/test/resources/baseline/extraction-legacy-before.json` |
| 기준 | `src/test/resources/baseline/structured-seed7-v1.json` |

권한을 확인하고 이 8개 파일을 업로드 목록에 포함하기로 승인한 경우에만 ignore 예외를 조정합니다. 원본 PDF 해시는 기존 기준 테스트가 확인합니다. `sample2/`는 별도 평가 자료이며 일반 전체 테스트 실행에 필수인 PDF 폴더는 아닙니다.

자료가 준비된 환경에서:

```powershell
mvn clean test

# 최근 원문 연결 보완 테스트만 실행
mvn test '-Dtest=PdfChosenBindingTest,ChosenPdfGenerationTest'
```

테스트는 생성·분포·seed 재현성, 편집·JSON 호환·수정본·전송, 풀이·복습, 원문 좌표·경로 보안·캐시·비동기 GUI 경쟁을 검사합니다. 일부 테스트는 실제 Swing 창을 띄우므로 화면이 없는 CI에서는 전체 EDT 테스트를 실행했다고 간주할 수 없습니다. 한글 PDF 합성 테스트는 Windows의 한글 폰트 가용성에도 영향을 받을 수 있습니다.

2026-11-23에 기존 로컬 테스트 자료가 있는 Windows/JDK 25 환경에서 `mvn clean test`를 실행해 **258개, 실패 0·오류 0·건너뜀 0, BUILD SUCCESS**를 확인했습니다. 이는 보호 자료가 빠진 새 clone에서 전체 테스트가 통과한다는 의미가 아닙니다.

같은 날 업로드 후보 110개 파일만 별도 임시 폴더에 복사해, PDF·기준 JSON 없이 `mvn package -DskipTests`가 **BUILD SUCCESS**인 것도 확인했습니다. 이 검사는 소스 구성의 컴파일·패키징 검사이며 전체 테스트 실행은 아닙니다.

분석 문서를 쓰는 도구의 `-Dquiz.writeDocs=true`나 기준 캡처 관련 프로퍼티는 일반 테스트 명령에 추가하지 마세요. 고정 기준을 자동 갱신해서 실패를 숨기지 않습니다.

## 프로젝트 구조

```text
java-pdf-quiz-generator/
├── README.md
├── pom.xml
├── run-gui.ps1
├── Agents.md
├── .gitignore
├── src/
│   ├── main/java/com/quiz/       # 추출·생성·모델·저장소·콘솔
│   │   └── gui/                 # Swing 화면·Workflow·풀이 세션
│   └── test/
│       ├── java/com/quiz/       # JUnit 및 분석/검증 도구
│       └── resources/          # 합성 legacy fixture, 별도 기준 자료
└── docs/                       # 일반 기술 문서와 로컬 분석 기록
```

`data/`는 사용 중 생성하는 문제·풀이 결과·수동 입력 자료이며 Git에서 제외합니다. 프로그램은 프로젝트 루트 기준 상대 경로를 사용합니다. `target/`, `tmp/`, `.m2-validation/`, 도구 로그와 `extracted.txt`도 제외합니다. 원본 PDF와 상세 분석 자료는 로컬에 남겨 두되 공유 승인 전 업로드하지 않습니다.

## 공개용 자체 제작 샘플 제안

현재 공개 구성에는 실행용 PDF가 포함되지 않습니다. 사용자가 이용 권한을 가진 텍스트 PDF를 선택하면 생성 기능을 사용할 수 있습니다. 전체 회귀 테스트에 필요한 위 8개 자료는 공개 저장소에 포함되지 않습니다.

후속 작업으로 강의 자료를 참조하지 않고 만든 가상의 개념·정의를 담은 작은 텍스트 PDF와, 그 내용으로 직접 작성한 JSON 예제를 추가할 수 있습니다. PDFBox로 PDF를 만들고 새로 작성한 예제의 이용 조건도 명시하는 방안을 권장합니다. 이 자료는 실행 시연용이며 기존 PDF·기준 JSON을 대체하지 않습니다. 기존 테스트 변경이나 기준 재생성 없이 별도 검증을 추가할 수 있습니다. 현재 업로드 후보에는 새 PDF나 새 테스트 데이터를 추가하지 않았습니다.

## Git과 GitHub CLI 준비

Windows에서 설치 여부를 확인합니다.

```powershell
git --version
gh --version
```

설치되지 않았다면 다음 명령을 사용할 수 있습니다. 설치 관리자가 권한을 요청하면 사용자가 직접 확인하고, 설치 후 VS Code와 터미널을 다시 열어 PATH를 갱신하세요.

```powershell
winget install --id Git.Git --exact --source winget
# GitHub CLI는 선택 사항: Git만으로도 push할 수 있습니다.
winget install --id GitHub.cli --exact --source winget
```

GitHub CLI를 사용하는 경우 `gh auth login`으로 사용자가 직접 인증합니다. 비밀번호·인증 코드·토큰을 README나 채팅에 적지 마세요. 저장소 생성과 최초 업로드는 최종 파일 목록 승인 후 진행합니다.

## GitHub 연결 후 변경 반영

업로드 승인 후 Git을 사용할 수 있는 환경에서 초기 저장소와 원격 `origin`을 연결합니다. 기존 Git 기록·remote가 있으면 먼저 확인하며 강제 push나 기록 초기화는 하지 않습니다. GitHub 인증은 Git Credential Manager 또는 GitHub CLI의 로그인 절차를 사용하고 비밀번호·토큰을 소스/README에 넣지 않습니다.

연결이 완료된 뒤 프로젝트 루트에서 일반적인 반영 순서는 다음과 같습니다.

```powershell
git status
git diff
# 테스트 자료를 준비한 로컬 환경에서 전체 검증
mvn clean test
# 변경된 필요한 파일만 명시적으로 선택
git add README.md
git diff --cached --stat
git diff --cached
git commit -m "Update project documentation"
git push
```

코드 수정 시에는 `git add`에 실제 변경 파일을 지정하세요. VS Code의 소스 제어에서도 diff 검토 → 선택한 파일 스테이징 → 커밋 → push 순서로 반영할 수 있습니다. Codex에도 변경 범위와 테스트, 커밋/push 승인 여부를 구분해 요청하면 됩니다. 이 저장소에 GitHub 연결이나 원격 URL이 이미 설정돼 있다는 의미는 아닙니다. 최초 연결 결과는 실제 작업 후 별도로 확인해야 합니다.
