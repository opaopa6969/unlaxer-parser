package org.unlaxer.base;

import java.util.HashMap;
import java.util.Map;

public class Kind extends Specifier<Kind>{

		private static final long serialVersionUID = 3434126976077675934L;

		public Kind(Class<?> specifiedClass, Enum<?> subKind) {
			super(specifiedClass, subKind);
		}

		public Kind(Class<?> specifiedClass, String subKind) {
			super(specifiedClass, subKind);
		}

		public Kind(Class<?> specifiedClass) {
			super(specifiedClass);
		}

		public Kind(Enum<?> enumKind) {
			super(enumKind);
		}

		public Kind(String stringKind) {
			super(stringKind);
		}
		
		public static Kind of(String kind){
			return specifierByString.computeIfAbsent(kind , Kind::new);
		}
		
		public static Kind of(Enum<?> kind){
			return specifierByEnum.computeIfAbsent(kind , Kind::new);
		}
		
		public static Kind of(Class<?> clazz){
			return specifierByClass.computeIfAbsent(clazz , Kind::new);
		}
		
		public static Kind of(Class<?> clazz,Specifier<?> kind){
			return specifierByString.get(clazz.getName()+"("+kind.toString()+")");
		}
		
		public static Kind of(Class<?> clazz,String kind){
			return specifierByString.get(clazz.getName()+"("+kind+")");
		}
		
		public static Kind classBaseOf(Object object){
			return specifierByString.get(object.getClass().getName());
		}
		
		static Map<Class<?>,Kind> specifierByClass =  new HashMap<>();
		
		static Map<String,Kind> specifierByString =  new HashMap<>();
		
		static Map<Enum<?>,Kind> specifierByEnum = new HashMap<>();
	}