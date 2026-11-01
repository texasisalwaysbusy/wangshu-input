#pragma once
#include <cstdint>
#include <numeric>
#include <string>
#include <vector>
#include <stdexcept>
// Original bounded exact arithmetic, no evaluator, binary floating point or dependencies.
// Windows first slice deliberately refuses values outside its smaller integer budget.
namespace wangshu::numeric {
constexpr std::int64_t IntegerLimit=9007199254740991LL;
constexpr size_t ExpressionLimit=128,TokenLimit=128,DepthLimit=24;
constexpr int ExponentLimit=16;
enum class Reason {None,EndingEquals,Syntax,Unsupported,Length,Tokens,Depth,Integer,Exponent,DivisionByZero,ZeroToZero};
struct Refusal {Reason reason;};
inline void refuse(Reason reason){throw Refusal{reason};}
inline std::int64_t magnitude(std::int64_t value){return value<0?-value:value;}
inline std::int64_t checked(std::int64_t value){if(value>IntegerLimit||value<-IntegerLimit)refuse(Reason::Integer);return value;}
inline std::int64_t add(std::int64_t a,std::int64_t b){if((b>0&&a>IntegerLimit-b)||(b<0&&a<-IntegerLimit-b))refuse(Reason::Integer);return a+b;}
inline std::int64_t multiply(std::int64_t a,std::int64_t b){if(b&&magnitude(a)>IntegerLimit/magnitude(b))refuse(Reason::Integer);return a*b;}
struct Rational {
 std::int64_t numerator=0,denominator=1;
 Rational(std::int64_t a=0,std::int64_t b=1):numerator(checked(a)),denominator(checked(b)){
  if(!b)refuse(Reason::DivisionByZero);if(denominator<0){numerator=-numerator;denominator=-denominator;}auto gcd=std::gcd(magnitude(numerator),denominator);numerator/=gcd;denominator/=gcd;
 }
 std::wstring text()const{return std::to_wstring(numerator)+(denominator==1?L"":L"/"+std::to_wstring(denominator));}
 Rational negated()const{return Rational(-numerator,denominator);}
 Rational plus(const Rational& b)const{auto g=std::gcd(denominator,b.denominator);return Rational(add(multiply(numerator,b.denominator/g),multiply(b.numerator,denominator/g)),multiply(denominator/g,b.denominator));}
 Rational times(const Rational& b)const{auto g=std::gcd(magnitude(numerator),b.denominator),h=std::gcd(magnitude(b.numerator),denominator);return Rational(multiply(numerator/g,b.numerator/h),multiply(denominator/h,b.denominator/g));}
 Rational divided(const Rational& b)const{if(!b.numerator)refuse(Reason::DivisionByZero);return times(Rational(b.denominator,b.numerator));}
 Rational power(const Rational& b)const{
  if(b.denominator!=1||magnitude(b.numerator)>ExponentLimit)refuse(Reason::Exponent);if(!numerator&&!b.numerator)refuse(Reason::ZeroToZero);
  auto exponent=b.numerator;Rational base=*this,result(1);if(exponent<0){base=Rational(denominator,numerator);exponent=-exponent;}
  while(exponent){if(exponent&1)result=result.times(base);exponent>>=1;if(exponent)base=base.times(base);}return result;
 }
};
struct Result {bool ready=false;std::wstring text;Reason reason=Reason::Unsupported;};
class Parser {
 const std::wstring& input;size_t at=0,tokens=0;
 void depth(size_t value){if(value>DepthLimit)refuse(Reason::Depth);}
 void spaces(){while(at<input.size()&&(input[at]==L' '||input[at]==L'\t'))++at;}
 wchar_t peek(){spaces();return at<input.size()?input[at]:0;}
 bool take(wchar_t value){if(peek()!=value)return false;++at;if(++tokens>TokenLimit)refuse(Reason::Tokens);return true;}
 Rational number(){
  spaces();size_t digits=0,fractionDigits=0;std::int64_t value=0,denominator=1;bool decimal=false;
  while(at<input.size()){
   wchar_t c=input[at];if(c>=L'0'&&c<=L'9'){++at;++digits;if(decimal&&++fractionDigits>9)refuse(Reason::Integer);value=add(multiply(value,10),c-L'0');if(decimal)denominator=multiply(denominator,10);}
   else if(c==L'.'&&!decimal){decimal=true;++at;}else break;
  }
  if(!digits)refuse(Reason::Syntax);if(++tokens>TokenLimit)refuse(Reason::Tokens);return Rational(value,denominator);
 }
 Rational primary(size_t d){depth(d);Rational value;if(take(L'(')){value=sum(d+1);if(!take(L')'))refuse(Reason::Syntax);}else{auto c=peek();if((c>=L'0'&&c<=L'9')||c==L'.')value=number();else refuse(c?Reason::Unsupported:Reason::Syntax);}if(take(L'²'))value=value.power(Rational(2));else if(take(L'³'))value=value.power(Rational(3));if(peek()==L'²'||peek()==L'³')refuse(Reason::Unsupported);return value;}
 Rational power(size_t d){auto value=primary(d);if(take(L'^'))value=value.power(unary(d+1));return value;}
 Rational unary(size_t d){depth(d);if(take(L'+'))return unary(d+1);if(take(L'-'))return unary(d+1).negated();return power(d);}
 Rational product(size_t d){auto value=unary(d);for(;;){if(take(L'*'))value=value.times(unary(d));else if(take(L'/'))value=value.divided(unary(d));else return value;}}
 Rational sum(size_t d){auto value=product(d);for(;;){if(take(L'+'))value=value.plus(product(d));else if(take(L'-'))value=value.plus(product(d).negated());else return value;}}
public:
 explicit Parser(const std::wstring& value):input(value){}
 Rational parse(){auto result=sum(0);if(peek())refuse(Reason::Syntax);return result;}
};
inline Result suggest(std::wstring expression){
 try{
  if(expression.size()>ExpressionLimit)refuse(Reason::Length);
  auto last=expression.find_last_not_of(L" \t");if(last==std::wstring::npos||expression[last]!=L'=')refuse(Reason::EndingEquals);expression.resize(last);
  std::wstring normalized;normalized.reserve(expression.size()*2);
  for(auto c:expression){if(c==L'−')c=L'-';if(c==L'×')c=L'*';if(c==L'÷')c=L'/';normalized+=c;}
  if(normalized.size()>ExpressionLimit)refuse(Reason::Length);auto result=Parser(normalized).parse();return {true,result.text(),Reason::None};
 }catch(const Refusal& error){return {false,L"",error.reason};}catch(...){return {false,L"",Reason::Unsupported};}
}
inline Result atCaret(const std::wstring& text,size_t caret){
 if(caret>text.size()||caret==0)return {};
 auto split=text.find_last_of(L"\n;；",caret-1);size_t from=split==std::wstring::npos?0:split+1;
 if(caret-from>ExpressionLimit)return {false,L"",Reason::Length};return suggest(text.substr(from,caret-from));
}
}
