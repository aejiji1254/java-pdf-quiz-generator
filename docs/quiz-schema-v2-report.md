# 문제 세트 JSON v2 구현 및 검증 보고

검증일: 2026-11-22. 최종 `mvn clean test`: **132 tests, 0 failures, 0 errors, 0 skipped / BUILD SUCCESS**.

## 동작

새 문제 세트는 UTF-8 JSON v2로 저장한다. v1은 계속 읽으며 읽기만으로 변환하거나 덮어쓰지 않는다. 파일 이름은 `세트 UUID.json`이고 CREATE_NEW를 유지한다. 결과 파일 형식과 AnswerRecord/QuizResult 검증은 변경하지 않았다.

v2 세트 필드는 `version`, `id`, `sourcePdf`, `questions`, `title`, `subject`, `createdAt`, `derivedFromSetId`다. 문항 필드는 기존 9개 필드와 `origin`, `derivedFromQuestionId`다. nullable 연결 필드도 키 자체는 필수다. 버전별 정확한 필드 집합, 자료형, UUID, Instant, enum을 검증한다.

모델의 기존 생성자 시그니처를 유지했다. 이전 세트 생성자는 기본 제목·빈 과목·미저장 시각 `Instant.EPOCH`·연결 null을 채운다. 이전 문항 생성자의 origin 기본값은 UNKNOWN이다. 실제 GUI/콘솔 저장 경로는 저장 직전에 Clock으로 createdAt을 부여한다. 저장소의 저수준 `save(SavedQuizSet)`는 전달된 불변 모델을 그대로 직렬화하므로 반환 모델과 파일의 생성일이 어긋나지 않는다. `edited()`와 `withQuestions()`는 메타데이터를 보존한다.

이름·과목 입력은 제어·서식 문자 검사 후 trim하며 코드 포인트 기준 이름 1~60자, 과목 0~30자다. Cc/Cf 문자는 앞뒤에 있더라도 제거해 수용하지 않고 거부한다. 기본 제목은 별도 안전한 문자열 함수로 만들며 Path를 사용하지 않는다. 수동 입력은 `수동 입력 - PDF 기본 이름`, PDF 미지정은 `수동 입력`이다. 기본값은 위험 표시 문자를 제거하고 60 code point로 자르며, 비면 `(제목 없음)`으로 대체한다. v1 파일명 제목도 먼저 안전하게 정규화하지만 v2의 잘못된 명시 제목은 거부한다.

저장 화면은 기본 제목과 기존 과목을 제공하며 과목 콤보박스에 기존 과목을 제안하고 직접 입력도 허용한다. 취소는 저장하지 않는다. 같은 이름도 별도 UUID로 저장한다. 수정본 기본 제목도 최대 길이 안에서 제안한다.

불러오기 목록의 열은 이름·과목·4지선다/OX 수·생성일·원본 PDF 이름이다. 생성일 최신순이며 표시 시각은 Asia/Seoul이다. 같은 시각은 ID로 안정적으로 정렬한다. 폴더 스캔은 Swing 의존성이 없는 QuizRepository.list()에 있고 GUI는 SwingWorker로 읽는다. 목록 선택 후에도 load()를 다시 호출해 파일을 재검증한다.

## 보완 요구사항 1~7 검증

| 번호 | 구현 및 검증 |
| --- | --- |
| 1. 제목 기본값 | QuizMetadata의 순수 문자열 처리. Windows/POSIX 혼합 구분자, 중간 콜론이 있는 수동 입력 출처, PDF 미지정, null/빈 파일명, 제어·서식 문자, 이모지 80개를 테스트했다. 이모지 60개 보존으로 UTF-16 길이 대신 codePointCount를 사용함을 검증했다. 실제 v1 리소스의 sourcePdf를 바꾼 테스트는 대체 제목으로 정상 로드되며, 같은 종류의 규칙 위반 v2 제목은 거부한다. ‘수동 입력’으로 시작하는 실제 PDF 파일명도 수동 출처 라벨과 구별한다. |
| 2. 콘솔 저장 | Main의 세트 저장·저장 후 풀이 경로가 v2 및 기본 제목·빈 과목을 사용한다. Clock 주입 오버로드를 추가했다. 콘솔에서 sample.pdf 저장 후 GUI가 사용하는 listQuizzes()로 확인하고, GUI workflow로 저장한 세트를 콘솔 load로 읽어 전 문항 정답 풀이하는 테스트가 통과했다. 기존 MainTest 11개도 수정 없이 통과했다. |
| 3. 목록 규칙 | 바로 아래의 일반 `.json` 파일만 스캔한다. NOFOLLOW_LINKS로 심볼릭 링크를 제외한다. v1·v2 정상 파일, 손상 JSON 7개, 잘못된 생성일, 파일명/ID 불일치, 잘못된 UTF-8, 5MB 초과 파일을 섞어 정상 2개·읽기 실패 11개·사유 최대 5개를 검증했다. `.json` 디렉터리·하위 JSON·txt는 무시한다. 실제 심볼릭 링크 생성은 이번 자동 테스트에 포함하지 않았으며 NOFOLLOW_LINKS 필터를 코드로 확인했다. |
| 4. 저장 시점 생성일 | mutable Clock으로 Preview 생성 후 시간을 이동시켜 저장했다. 최초 저장과 수정본 저장 모두 이동한 시각이 반환 모델과 복원 모델에서 일치한다. 콘솔 저장도 고정 Clock 시각을 확인했다. v1은 파일 수정 시각을 읽기 전용으로 사용하며 바이트와 수정 시각이 유지된다. |
| 5. Cc/Cf | Java Character가 Cc/Cf로 분류하는 전체 코드 포인트를 순회하여 이름·과목 모두 거부함을 검증했다. 공백 이름, 61자, 과목 31자, 이름 1자·60 code point, 과목 0·30자, trim도 확인했다. Swing JTextField의 줄바꿈 자동 치환을 꺼서 붙여넣기 입력도 검증 전에 공백으로 바뀌지 않게 했고, EDT에서 줄바꿈·제로폭·방향 제어 문자의 입력 보존 및 거부를 확인했다. |
| 6. 기존 테스트 변경 | 아래 표 참조. 기존 바이트 비교·복습 동일성 검증은 삭제하거나 완화하지 않았다. 고정 v1 세트와 기존 결과 리소스로 정상 저장·실패·입력 거부 후 보존을 확인한다. |
| 7. Swing 분리 | 목록 테스트는 Swing 없이 파일 시스템과 저장소만 사용한다. EDT 테스트는 로딩 표시→목록 표시→선택→검토 화면 이동을 검증하고 저장소 스캔이 EDT 밖에서 실행되는지도 확인한다. 이름 폼은 제안 목록·직접 입력·오류 후 입력 유지·취소 시 저장 값 미반환을 별도 EDT 테스트로 확인했다. |

## 연결 정보 규칙별 검증

`QuizSchemaV2Test.generationManualAdditionRevisionAndSecondRevisionRecordImmediateOrigins()`가 다음 경로를 검증한다. `QuizRevisionTest`는 기존 v1의 UNKNOWN/MANUAL 값도 수정본에서 보존되는지 확인한다.

| 경로 | 기대값과 검증 |
| --- | --- |
| PDF 생성 | 실제 sample.pdf 생성 결과가 비어 있지 않으며 모든 origin=GENERATED, 문항 연결=null |
| 수동 용어에서 생성 | generateManual() 결과 origin=GENERATED. 해설 접두어로 추정하지 않는다. |
| 직접 작성 | addQuestion() 결과 origin=MANUAL, 연결=null. 해설 접두어는 표시용이다. |
| 수정본의 기존 문항 | 새 UUID, origin 유지, derivedFromQuestionId=바로 직전 원본 문항 ID. 세트 derivedFromSetId=원본 세트 ID |
| 수정본에서 추가한 직접 작성 문항 | origin=MANUAL, derivedFromQuestionId=null |
| 재수정본 | derivedFromSetId=직전 수정본 ID. 모든 기존 문항의 derivedFromQuestionId=직전 수정본 문항 ID. 최초 원본으로 건너뛰지 않는다. |
| 편집 | edited()가 id·origin·derivedFromQuestionId를 유지. Preview 재구성도 세트 메타데이터를 유지 |

## 기존 테스트 변경 내역

| 기존 테스트/도우미 | 변경 내용 | 검증 삭제·완화 여부 |
| --- | --- | --- |
| QuizRepositoryTest.rejectsBrokenJsonMissingFieldsAndWrongAnswerIndex | 새 저장본의 중복 version 키를 만들 때 치환 대상을 version 1에서 2로 변경 | 없음. 손상 JSON·누락 필드·잘못된 정답·중복 키 거부를 모두 유지 |
| QuizRevisionTest.saveOriginalAndExistingResult (@Before) | 동적 세트/결과 생성 대신 고정 quiz-v1.json/result-v1.json을 복사해 읽음 | 없음. @After의 원본/결과 바이트 비교, 원본 복원 동일성, 기존 결과 및 복습 동일성 검증을 그대로 유지. 리소스 자체는 런타임에 쓰지 않고 임시 폴더에 복사 |
| QuizRevisionTest.editsBothTypesDeletesAndSavesNewV1SetWithoutBreakingOriginalResults → editsBothTypesDeletesAndSavesNewV2SetWithoutBreakingV1Results | 이름과 출력 버전 기대값을 v2로 변경. 세트/문항의 원본 연결 ID 검증 추가 | v1로 새 파일을 써야 한다는 기대값만 요구사항에 맞춰 대체. 기존 내용·UUID·출처·풀이·이전 결과 오연결 거부 검증 유지 |
| QuizRevisionTest.assertContentAndSource | 새 record 필드가 생겨도 기존 문항 필드를 계속 비교하도록 정규화 생성자에 origin/이전 연결 값을 명시. origin 동일성 검증 추가 | 기존 필드 검증 삭제 없음. 저장 시 달라지는 연결 ID는 수정본 테스트와 새 provenance 테스트에서 별도로 실제 기대 ID와 비교 |

그 밖의 기존 테스트 파일과 검증 문장은 변경하지 않았다. 특히 MainTest·QuizWorkflowTest·DirectQuestionTest·QuestionCreationGuiTest·결과 저장소 및 재시도 테스트를 그대로 실행했다.

## 변경 파일과 이유

| 파일 | 이유 |
| --- | --- |
| src/main/java/com/quiz/QuizMetadata.java (추가) | 안전한 파일명·기본 제목과 공통 메타데이터 검증 |
| src/main/java/com/quiz/SavedQuizSet.java | v2 필드, 호환 생성자, 불변 메타데이터 유지 도우미 |
| src/main/java/com/quiz/SavedQuestion.java | origin·문항 연결과 편집 시 보존 |
| src/main/java/com/quiz/QuizRepository.java | 엄격한 v1/v2 읽기, v2 쓰기, 파일별 오류 격리 목록 |
| src/main/java/com/quiz/Main.java | 콘솔 저장 메타데이터·Clock 적용 |
| src/main/java/com/quiz/gui/QuizWorkflow.java | 저장 시각·메타데이터, 생성 origin, 직전 원본 연결, 목록 중계 |
| src/main/java/com/quiz/gui/QuizMakerFrame.java | 저장 이름 입력, 비동기 목록과 선택 후 검토 화면 연결 |
| src/main/java/com/quiz/gui/QuizMetadataPanel.java (추가) | 이름·편집 가능한 과목 폼 및 취소/검증 |
| src/main/java/com/quiz/gui/QuizLibraryPanel.java (추가) | 목록 열·로딩 표시·최대 5개 오류 사유 표시 |
| src/test/java/com/quiz/QuizSchemaV2Test.java (추가) | v1/v2·메타데이터·목록·콘솔 교차·저장 시각·provenance 테스트 14개 |
| src/test/java/com/quiz/gui/QuizLibraryGuiTest.java (추가) | 목록 EDT 흐름과 메타데이터 폼 테스트 2개 |
| src/test/java/com/quiz/LegacyFixtures.java (추가) | 고정 legacy 리소스 복사 도우미 |
| src/test/resources/legacy/quiz-v1.json (추가) | 실제 스키마 v1 세트 샘플 |
| src/test/resources/legacy/result-v1.json (추가) | 해당 세트의 기존 v1 결과 샘플 |
| src/test/java/com/quiz/QuizRepositoryTest.java | 중복 키 거부 테스트의 새 저장 버전 반영 |
| src/test/java/com/quiz/gui/QuizRevisionTest.java | 고정 v1 바이트 보존과 v2 수정본 기대값·연결 검증 |
| docs/quiz-schema-v2-report.md (추가) | 이 구현 및 검증 보고 |

첫 v2 전체 테스트는 잘못된 createdAt의 DateTimeParseException이 IOException으로 변환되지 않는 오류 1개를 발견했다. 날짜 파싱 오류를 파일 검증 오류로 변환하고 혼합 폴더에 잘못된 날짜 파일도 추가했다. 수정 후 최종 clean 테스트 132개가 모두 통과했다.
