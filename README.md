# 양 세는 밤 — Backend

Spring Boot와 Kotlin으로 개발하는 양 세는 밤 백엔드입니다. API 명세 v0.6을 기준으로 BE1·BE2·BE3의 담당 영역을 구분합니다.

현재는 **전체 47개 API의 컨트롤러, 요청·응답 DTO, 서비스 진입점으로 구성된 스켈레톤**입니다. 실제 인증, DB 연동, 업무 로직은 각 담당자가 구현합니다. 미구현 서비스는 `501 NOT_IMPLEMENTED`를 반환하며, 보호 API는 인증 구현 전까지 익명 요청을 허용하지 않습니다.

## 개발 환경

| 항목 | 버전 / 설정 |
| --- | --- |
| Java | 21 |
| Kotlin | 2.3.21 |
| Spring Boot | 4.1.1 |
| Gradle Wrapper | 9.5.1 |
| 공통 DB | MySQL 8.4 / Spring JDBC / Flyway |
| 기본 패키지 | `com.yangsenunbam` |
| API 기본 경로 | `/api/v1` |
| JSON 필드 | `snake_case` |

```bash
./gradlew bootRun
./gradlew test
./gradlew bootJar
```

## 공통 DB 실행

BE1·BE2·BE3가 한 Spring Boot 애플리케이션과 MySQL DB를 사용합니다. 기본 프로필은 스켈레톤 실행과 테스트용 H2를 사용합니다. 로컬 MySQL 연동은 `mysql` 프로필로 실행합니다.

```bash
# 로컬 개발 전용 값입니다. 팀 서버에서는 별도 비밀번호를 사용합니다.
export DB_PASSWORD=local-dev-password
export MYSQL_ROOT_PASSWORD=local-root-password
docker compose -f compose.mysql.yml up -d --wait
SPRING_PROFILES_ACTIVE=mysql ./gradlew bootRun
```

기본 DB 이름과 사용자는 `yangsenunbam`, 로컬 접속 주소는 `jdbc:mysql://127.0.0.1:3306/yangsenunbam`입니다. `MYSQL_PORT`로 Compose의 로컬 포트를 바꾸면 `DB_URL`의 포트도 함께 바꿉니다. 데이터는 Compose 볼륨에 저장됩니다. 개발을 마친 뒤 `docker compose -f compose.mysql.yml down`으로 컨테이너를 종료할 수 있습니다.

| 공통 파일 | 역할 |
| --- | --- |
| [build.gradle.kts](build.gradle.kts) | Spring JDBC, MySQL 드라이버, Flyway 의존성 |
| [application.yml](src/main/resources/application.yml) | 기본 설정과 `prod` 프로필 그룹 |
| [application-mysql.yml](src/main/resources/application-mysql.yml) | DB 접속, 연결 풀, UTC, Flyway 설정과 RDS TLS 설정 |
| [compose.mysql.yml](compose.mysql.yml) | 로컬 MySQL 8.4와 데이터 볼륨 |
| [db/migration/mysql/](src/main/resources/db/migration/mysql) | 세 파트가 사용하는 MySQL 마이그레이션 경로 |

### 마이그레이션과 파트 연동

- 각 파트는 `src/main/resources/db/migration/mysql/`에 `V<번호>__<설명>.sql`을 추가합니다. Flyway 버전은 팀 전체에서 하나의 순서로 관리합니다. BE1의 기존 `V1`·`V2` 다음 번호부터 충돌 없이 배정하고, 적용한 SQL을 수정해야 하면 후속 버전을 추가합니다.
- MySQL 프로필에서는 Flyway가 시작 시 스키마를 적용합니다. 첫 통합 검증은 빈 개발 DB에서 시작합니다. 기존 DB를 이어 쓰려면 적용된 Flyway 이력과 실제 테이블을 확인한 뒤 전환합니다.
- BE1의 `auth_users`, `user_onboarding`은 인증·생년월일의 원본입니다. 밤·세션은 BE1과 BE2가 공통으로 사용할 테이블을 정한 뒤 생성·종료·진행 코드를 연결합니다. 현재 기능 브랜치의 `be1_nights`·`be1_sessions`와 `night_records`·`feed_sessions`는 서로 다른 테이블입니다.
- BE3의 검수 카드와 BE2가 채택한 양을 서로 조회할 수 있도록 카드 ID, 양 ID와 각 파트의 갱신 범위를 맞춥니다. BE2의 기존 `db/migration/be2/` 스크립트와 별도 Flyway 이력은 기능 통합 때 공통 경로로 정리합니다.

### AWS RDS 설정

서버에서 `SPRING_PROFILES_ACTIVE=prod`를 지정하면 `mysql` 설정도 활성화됩니다. `DB_URL`에 RDS의 JDBC 주소(`jdbc:mysql://<RDS 주소>:3306/yangsenunbam`)를 지정하고 `DB_USERNAME`, `DB_PASSWORD`, `DB_TRUSTSTORE_URL`, `DB_TRUSTSTORE_PASSWORD`를 서버 환경변수로 전달합니다. `DB_TRUSTSTORE_URL`은 RDS CA를 넣은 PKCS12 파일의 `file:` URL입니다. 프론트에는 배포된 백엔드의 `/api/v1` 주소를 전달합니다.

## 담당 영역 요약

| 담당 | 역할 | 담당 패키지 | API 수 |
| --- | --- | --- | --- |
| **BE1** | 계정·인증·온보딩·밤 기록·세션 생성과 종료 | `auth`, `user`, `bootstrap`, `night`, `session` | 22 |
| **BE2** | 피드·진행 저장·세션 재개·아침·양 채택·운세 | `feed`, `morning`, `fortune` | 11 |
| **BE3** | 카드 작성·검수·곡 검색·털실·양떼 조회와 꾸미기 | `card`, `music`, `flock`, `yarn`, `admin` | 14 |

아래 패키지의 기준 경로는 [`src/main/kotlin/com/yangsenunbam`](src/main/kotlin/com/yangsenunbam)입니다. 파일명을 누르면 해당 코드로 이동합니다.

## BE1 — 계정과 밤 상태

| 경로 | 역할 | 담당 파일 |
| --- | --- | --- |
| [`auth/`](src/main/kotlin/com/yangsenunbam/auth) | 가입, 로그인, 토큰 갱신, 로그아웃, 비밀번호 재설정 | [AuthController.kt](src/main/kotlin/com/yangsenunbam/auth/AuthController.kt), [AuthService.kt](src/main/kotlin/com/yangsenunbam/auth/AuthService.kt), [AuthDtos.kt](src/main/kotlin/com/yangsenunbam/auth/AuthDtos.kt) |
| [`user/`](src/main/kotlin/com/yangsenunbam/user) | 내 정보, 약관 문서, 동의, 낮 안내 확인, 생년월일, 알림, 고민 목록, 탈퇴 | [UserController.kt](src/main/kotlin/com/yangsenunbam/user/UserController.kt), [UserService.kt](src/main/kotlin/com/yangsenunbam/user/UserService.kt), [UserDtos.kt](src/main/kotlin/com/yangsenunbam/user/UserDtos.kt) |
| [`bootstrap/`](src/main/kotlin/com/yangsenunbam/bootstrap) | 앱 진입 시 시간·사용자 상태·다음 화면 결정 | [BootstrapController.kt](src/main/kotlin/com/yangsenunbam/bootstrap/BootstrapController.kt), [BootstrapService.kt](src/main/kotlin/com/yangsenunbam/bootstrap/BootstrapService.kt), [BootstrapDtos.kt](src/main/kotlin/com/yangsenunbam/bootstrap/BootstrapDtos.kt) |
| [`night/`](src/main/kotlin/com/yangsenunbam/night) | 카테고리, 밤 고민 기록·삭제, 도움 안내 후 계속하기, 작성 제안 선택 | [NightController.kt](src/main/kotlin/com/yangsenunbam/night/NightController.kt), [NightService.kt](src/main/kotlin/com/yangsenunbam/night/NightService.kt), [NightDtos.kt](src/main/kotlin/com/yangsenunbam/night/NightDtos.kt) |
| [`session/`](src/main/kotlin/com/yangsenunbam/session) | 세션 생성, 명시적 종료, 종료 요약·마무리 카드 응답 | [SessionController.kt](src/main/kotlin/com/yangsenunbam/session/SessionController.kt), [SessionService.kt](src/main/kotlin/com/yangsenunbam/session/SessionService.kt), [SessionDtos.kt](src/main/kotlin/com/yangsenunbam/session/SessionDtos.kt) |

인증 구현은 [`security/SecurityConfig.kt`](src/main/kotlin/com/yangsenunbam/security/SecurityConfig.kt)에 연결합니다. 다른 파트가 사용하는 인증 사용자 타입은 [`security/CurrentUser.kt`](src/main/kotlin/com/yangsenunbam/security/CurrentUser.kt)입니다.

`POST /nights/{night_id}/sessions`와 `POST /sessions/{session_id}/finish`는 BE1 담당입니다. 피드 조회·진행 저장·재개는 BE2 담당입니다.

## BE2 — 피드와 아침

| 경로 | 역할 | 담당 파일 |
| --- | --- | --- |
| [`feed/`](src/main/kotlin/com/yangsenunbam/feed) | 피드 조회, 세션 재개, 진행 저장, 쉼표 응답, 마무리 카드 열람 확인, 솜털·노래 담기 | [FeedController.kt](src/main/kotlin/com/yangsenunbam/feed/FeedController.kt), [FeedService.kt](src/main/kotlin/com/yangsenunbam/feed/FeedService.kt), [FeedDtos.kt](src/main/kotlin/com/yangsenunbam/feed/FeedDtos.kt), [ProgressDtos.kt](src/main/kotlin/com/yangsenunbam/feed/ProgressDtos.kt) |
| [`morning/`](src/main/kotlin/com/yangsenunbam/morning) | 아침 기록·후보 카드 조회, 양 채택, 회고 저장 | [MorningController.kt](src/main/kotlin/com/yangsenunbam/morning/MorningController.kt), [MorningService.kt](src/main/kotlin/com/yangsenunbam/morning/MorningService.kt), [MorningDtos.kt](src/main/kotlin/com/yangsenunbam/morning/MorningDtos.kt) |
| [`fortune/`](src/main/kotlin/com/yangsenunbam/fortune) | 별자리별 오늘의 운세 조회 | [FortuneController.kt](src/main/kotlin/com/yangsenunbam/fortune/FortuneController.kt), [FortuneService.kt](src/main/kotlin/com/yangsenunbam/fortune/FortuneService.kt), [FortuneDtos.kt](src/main/kotlin/com/yangsenunbam/fortune/FortuneDtos.kt) |

- `ProgressRequest`, `ProgressResponse`는 **`feed/ProgressDtos.kt`**에서 관리합니다.
- 세션 재개 응답은 BE1의 `session/SessionDtos.kt`에 있는 `SessionResponse`를 재사용합니다.
- 양 채택은 BE2 담당이고, 채택한 양의 목록·상세 조회와 꾸미기는 BE3 담당입니다.

## BE3 — 콘텐츠와 양떼

| 경로 | 역할 | 담당 파일 |
| --- | --- | --- |
| [`card/`](src/main/kotlin/com/yangsenunbam/card) | 카드 제출, 내 카드 목록·상태 필터, 삭제, 반려 카드 재제출 | [CardController.kt](src/main/kotlin/com/yangsenunbam/card/CardController.kt), [CardService.kt](src/main/kotlin/com/yangsenunbam/card/CardService.kt), [CardDtos.kt](src/main/kotlin/com/yangsenunbam/card/CardDtos.kt) |
| [`music/`](src/main/kotlin/com/yangsenunbam/music) | 곡 검색과 곡 정보 응답 | [MusicController.kt](src/main/kotlin/com/yangsenunbam/music/MusicController.kt), [MusicService.kt](src/main/kotlin/com/yangsenunbam/music/MusicService.kt), [MusicDtos.kt](src/main/kotlin/com/yangsenunbam/music/MusicDtos.kt) |
| [`flock/`](src/main/kotlin/com/yangsenunbam/flock) | 양떼 목록·상세, 스티커 목록, 스티커 배치 저장 | [FlockController.kt](src/main/kotlin/com/yangsenunbam/flock/FlockController.kt), [FlockService.kt](src/main/kotlin/com/yangsenunbam/flock/FlockService.kt), [FlockDtos.kt](src/main/kotlin/com/yangsenunbam/flock/FlockDtos.kt) |
| [`yarn/`](src/main/kotlin/com/yangsenunbam/yarn) | 털실 보내기·취소, 받은 털실 요약, 확인 처리 | [YarnController.kt](src/main/kotlin/com/yangsenunbam/yarn/YarnController.kt), [YarnService.kt](src/main/kotlin/com/yangsenunbam/yarn/YarnService.kt), [YarnDtos.kt](src/main/kotlin/com/yangsenunbam/yarn/YarnDtos.kt) |
| [`admin/`](src/main/kotlin/com/yangsenunbam/admin) | 운영자 검수 대기 목록, 카드 승인·반려 | [ModerationController.kt](src/main/kotlin/com/yangsenunbam/admin/ModerationController.kt), [ModerationService.kt](src/main/kotlin/com/yangsenunbam/admin/ModerationService.kt), [ModerationDtos.kt](src/main/kotlin/com/yangsenunbam/admin/ModerationDtos.kt) |

양떼 코드의 폴더명은 `flock`이며, 명세의 API 주소인 `/sheep`는 그대로 사용합니다. `CardResponse`, `TrackResponse`, `SheepResponse`는 다른 파트에서도 참조합니다.

## 공통 파일과 파트 간 연결

| 파일 | 용도 |
| --- | --- |
| [common/ApiTypes.kt](src/main/kotlin/com/yangsenunbam/common/ApiTypes.kt) | 목록·페이지 응답, 카테고리·상태 enum, 도움 안내 타입 |
| [common/ApiException.kt](src/main/kotlin/com/yangsenunbam/common/ApiException.kt) | 오류 코드, 업무 예외, 오류 응답 |
| [common/ApiExceptionHandler.kt](src/main/kotlin/com/yangsenunbam/common/ApiExceptionHandler.kt) | 공통 HTTP 오류 처리 |
| [common/TextLength.kt](src/main/kotlin/com/yangsenunbam/common/TextLength.kt) | 유니코드 코드 포인트 기준 문자 수 검증 |
| [common/NoRequestBody.kt](src/main/kotlin/com/yangsenunbam/common/NoRequestBody.kt) | 본문을 받지 않는 API 표시 |
| [config/WebConfig.kt](src/main/kotlin/com/yangsenunbam/config/WebConfig.kt) | 쿼리 값 변환, 요청 본문 검사 |
| [security/SecurityConfig.kt](src/main/kotlin/com/yangsenunbam/security/SecurityConfig.kt) | 공개·인증·온보딩·운영자 접근 범위 |
| [security/CurrentUser.kt](src/main/kotlin/com/yangsenunbam/security/CurrentUser.kt) | 인증된 사용자 정보 |
| [application.yml](src/main/resources/application.yml) | 애플리케이션과 JSON 공통 설정 |
| [build.gradle.kts](build.gradle.kts) | 플러그인, 의존성, 빌드·테스트 설정 |

공통 파일과 공유 DTO를 변경할 때는 사용하는 파트와 변경 내용을 맞춥니다. 같은 DTO를 각 파트에 중복 정의하지 않습니다.

| 공유 대상 | 연결되는 작업 |
| --- | --- |
| 밤·세션 | BE1이 생성·종료하고 BE2가 재개·진행·열람을 기록 |
| 카드 | BE3이 관리하는 카드를 BE1의 마무리와 BE2의 피드·아침에서 사용 |
| 양 | BE2가 채택한 양을 BE3이 조회·꾸미기 |
| 사용자·인증 | BE1이 제공하는 사용자 정보를 전체 API에서 사용 |

공통 DB 설정은 `COMMON_mysql_db`에서 관리합니다. 업무 테이블과 저장소 구현은 각 기능 브랜치에서 관리하며, 공유 데이터가 필요한 부분은 위 마이그레이션과 파트 연동 기준을 따릅니다.

## 테스트와 브랜치

테스트 기준 경로는 [`src/test/kotlin/com/yangsenunbam`](src/test/kotlin/com/yangsenunbam)입니다.

| 파일 | 검증 내용 |
| --- | --- |
| [YangsenunbamApplicationTests.kt](src/test/kotlin/com/yangsenunbam/YangsenunbamApplicationTests.kt) | Spring 컨텍스트 기동 |
| [ApiSkeletonTest.kt](src/test/kotlin/com/yangsenunbam/ApiSkeletonTest.kt) | 47개 API 경로, 입력 검증, 접근 범위, 미구현 서비스의 501 응답 |
| [ResponseContractTest.kt](src/test/kotlin/com/yangsenunbam/ResponseContractTest.kt) | v0.6 응답 필드와 JSON 형식 |
| [ControllerStatusTest.kt](src/test/kotlin/com/yangsenunbam/ControllerStatusTest.kt) | 생성·접수·본문 없는 성공 응답 상태 코드 |

서비스를 구현하면 해당 API가 `501`을 반환한다는 기존 테스트도 실제 동작에 맞게 갱신합니다. 스켈레톤 테스트 통과는 DB·외부 서비스 통합 완료를 의미하지 않습니다.

기능 개발은 최신 `main`에서 담당 브랜치를 만들어 진행합니다. 이름은 `BE1_auth`, `BE2_feed_morning`, `BE3_cards`처럼 담당과 기능을 함께 표시합니다. `main`에는 합의한 공통 변경이나 검토를 마친 기능만 반영합니다.
