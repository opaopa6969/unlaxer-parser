package org.unlaxer;

public class CircularReferenceTest {

//	public static class A {
//		B b;
//
//		public A() {
//			super();
//			this.b = new B();
//		}
//	}
//
//	public static class B {
//		A a;
//
//		public B() {
//			super();
//			this.a = new A();
//		}
//
//	}
//
//	@Test
//	@Ignore
//	public void testUnSafe() {
//		try {
//			new A();
//			fail();
//		} catch (StackOverflowError e) {
//			System.out.println("StackOverflowError is expected!");
//		}
//	}
//
//	static class SafeA {
//
//		static final SafeA instance = new SafeA();
//		boolean initialized;
//
//		public SafeB b;
//
//		public SafeA() {
//			super();
//		}
//
//		public void init() {
//			if (false == initialized) {
//				initialized = true;
//				b = SafeB.instance();
//			}
//		}
//
//		public static SafeA instance() {
//			instance.init();
//			return instance;
//		}
//	}
//
//	static class SafeB {
//
//		static final SafeB instance = new SafeB();
//		boolean initialized;
//
//		public SafeA a;
//
//		public SafeB() {
//			super();
//		}
//
//		public void init() {
//			if (false == initialized) {
//				initialized = true;
//				a = SafeA.instance();
//			}
//		}
//
//		public static SafeB instance() {
//			instance.init();
//			return instance;
//		}
//	}
//
//	@Test
//	@Ignore
//	public void testSafe() {
//		try {
//			SafeA a = SafeA.instance();
//
//			assertTrue(a.b != null);
//
//			assertTrue(a.b.a != null);
//
//		} catch (StackOverflowError e) {
//			fail();
//		}
//	}
}
