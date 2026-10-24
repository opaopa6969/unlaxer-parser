# verifier 用の実 Surefire XML

Maven Surefire 3.2.5 / JUnit 4.13.2 / JDK 21.0.9 で実際に生成した XML。
環境依存の `properties` と stdout/stderr だけを除き、カウンター、testcase、
skip/failure/error の構造とスタックトレースを保持している。
新しいテスト依存を追加せず、既存の Surefire / JUnit を使った。

`fixture.MethodTest` の各メソッドを `-Dtest=fixture.MethodTest#<method>` で個別に実行した。

```java
@Test public void success() { Assert.assertTrue(true); }
@Test public void skipped() { Assume.assumeTrue("opt-in disabled", false); }
@Test public void failure() { Assert.assertEquals("oracle mismatch", 1, 2); }
@Test public void error() { throw new IllegalStateException("probe failed"); }
```

`before-class.xml` は `fixture.BeforeClassTest` の `@BeforeClass` で
`Assume.assumeTrue("class opt-in disabled", false)` を実行した形状。
この場合、メソッド名が空の testcase が 1 件出るため、クラス名や tests=1 だけでは成功にできない。

`test_verify_optin_tests.py` はこれらを直接読み、欠落・ゼロ実行・不正なカウンター・
parameterized 名・古い XML の消去・無関係な意図した skip も検証する。
