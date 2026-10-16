class DumpLegacyCase {
 public static void main(String[]args) {
  for(int cp=0;cp<=0x10ffff;cp++) {
   if(cp>=0xd800 && cp<=0xdfff)continue;
   int upper=Character.toUpperCase(cp), lower=Character.toLowerCase(cp), fold=Character.toLowerCase(upper);
   if(upper!=cp || lower!=cp)System.out.printf("%X;%X;%X;%X%n",cp,upper,lower,fold);
  }
 }
}
