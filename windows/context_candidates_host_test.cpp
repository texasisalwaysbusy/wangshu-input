// Small native host execution only. This is not PE, TSF or Windows UI acceptance.
#include "context_candidates.h"
#include <functional>
#include <iostream>
#include <stdexcept>
using namespace wangshu::context_math;
static int passed=0,failed=0;
static void expect(bool value){if(!value)throw std::runtime_error("context contract assertion");}
static void test(const char* name,const std::function<void()>& run){try{run();++passed;std::cout<<"PASS "<<name<<'\n';}catch(const std::exception& e){++failed;std::cout<<"FAIL "<<name<<" :: "<<e.what()<<'\n';}}
static void ready(std::wstring_view context,std::wstring_view expression,std::wstring_view text,Kind kind=Kind::Substitution){auto r=suggest(context,expression);expect(r.status==Status::Ready&&r.candidate&&r.candidate->text==text&&r.candidate->kind==kind&&std::wstring(r.candidate->label())==(kind==Kind::Value?L"精确结果":L"代入"));}
static void refuse(std::wstring_view context,std::wstring_view expression,Reason reason,Status status=Status::Unsupported){auto r=suggest(context,expression);expect(r.status==status&&!r.candidate&&r.reason==reason);}
static void arithmetic(std::wstring_view context,std::wstring_view expression,wangshu::numeric::Reason reason){auto r=suggest(context,expression);expect(r.status==Status::Unsupported&&!r.candidate&&r.reason==Reason::ArithmeticRejected&&r.arithmetic==reason);}
static std::wstring balanced(size_t n){if(n==1)return L"a";size_t left=n/2;return L"("+balanced(left)+L"+"+balanced(n-left)+L")";}
int main(){
 test("user comma assignment example",[]{ready(L"a=j,b=1,c=h",L"a²+b²−c=",L"j²+1²−h");});
 test("user same-line spaced definitions",[]{ready(L"a=j b=1 c=h",L"a²+b²−c=",L"j²+1²−h");});
 test("CRLF and semicolon definitions",[]{ready(L"a=j\r\nb=1;c=h",L"a²+b²−c=",L"j²+1²−h");});
 test("compound power base parentheses",[]{ready(L"a=j+1",L"a²=",L"(j+1)²");});
 test("negative power base parentheses",[]{ready(L"a=−j",L"a²=",L"(−j)²");});
 test("fractional literal power base parentheses",[]{ready(L"a=.5",L"a²+j=",L"(1/2)²+j");});
 test("numeric definitions use deterministic exact rational",[]{ready(L"a=1/3;b=1/6",L"a+b=",L"1/2",Kind::Value);});
 test("unknown variable retained after real substitution",[]{ready(L"a=j;c=h",L"a²+b²−c=",L"j²+b²−h");});
 test("identifier substitution is not substring replacement",[]{ready(L"a=j",L"alpha+a=",L"alpha+j");});
 test("cycle is ambiguous",[]{refuse(L"a=b;b=a",L"a+1=",Reason::Cycle,Status::Ambiguous);});
 test("unused cycle is also ambiguous",[]{refuse(L"a=b;b=a;c=1",L"c+j=",Reason::Cycle,Status::Ambiguous);});
 test("conflicting duplicate is ambiguous",[]{refuse(L"a=1;a=2",L"a+1=",Reason::Conflict,Status::Ambiguous);});
 test("normalized identical duplicate is allowed",[]{ready(L"a=1;a=01",L"a+1=",L"2",Kind::Value);});
 test("structural duplicate does not pretend CAS equivalence",[]{refuse(L"a=1+1;a=2",L"a+1=",Reason::Conflict,Status::Ambiguous);});
 test("undefined-only input has no echo candidate",[]{refuse(L"",L"x+1=",Reason::NoDefinedSubstitution);});
 test("unrelated definitions do not create echo candidate",[]{refuse(L"a=1",L"x+1=",Reason::NoDefinedSubstitution);});
 test("unknown exponent is not guessed",[]{refuse(L"a=j",L"2^a=",Reason::UnknownExponent);});
 test("known zero denominator rejected despite unknown numerator",[]{arithmetic(L"a=j/(1-1)",L"a+1=",wangshu::numeric::Reason::DivisionByZero);});
 test("noninteger symbolic exponent rejected",[]{arithmetic(L"a=j",L"a^(1/2)=",wangshu::numeric::Reason::Exponent);});
 test("large symbolic exponent rejected",[]{arithmetic(L"a=j",L"a^17=",wangshu::numeric::Reason::Exponent);});
 test("function-like definition is not executable",[]{auto r=suggest(L"a=system(1)",L"a+1=");expect(r.status==Status::Unsupported&&!r.candidate);});
 test("equation solving is not inferred",[]{refuse(L"x+y=3",L"x+1=",Reason::InvalidAssignment);});
 test("missing assignment separator rejected",[]{refuse(L"a=1b=2",L"a+b=",Reason::InvalidAssignment);});
 test("function-like expression rejected",[]{auto r=suggest(L"f=j",L"f(x)=");expect(r.status==Status::Unsupported&&!r.candidate);});
 test("context budget checked before parsing",[]{refuse(std::wstring(1025,L'a'),L"x+1=",Reason::ContextLimit);});
 test("context limit counts UTF16 not Linux wchar scalars",[]{std::wstring c;for(int i=0;i<513;++i)c+=L"\U0001F600";refuse(c,L"x+1=",Reason::ContextLimit);});
 test("expression budget checked before copying",[]{refuse(L"a=j",std::wstring(128,L' ')+L"a=",Reason::ExpressionLimit);});
 test("identifier limit enforced",[]{refuse(std::wstring(33,L'a')+L"=j",L"a+1=",Reason::IdentifierLimit);});
 test("unique variable limit shared across context and expression",[]{std::wstring c;for(int i=0;i<17;++i)c+=L"a"+std::to_wstring(i)+L"=1;";refuse(c,L"a0+j=",Reason::VariableLimit);});
 test("token budget shared across entire context",[]{std::wstring c=L"a=1";for(int i=0;i<70;++i)c+=L"+1";refuse(c,L"a+j=",Reason::TokenLimit);});
 test("nesting limit enforced",[]{refuse(L"a=j",std::wstring(25,L'(')+L"a"+std::wstring(25,L')')+L"=",Reason::DepthLimit);});
 test("exponential alias expansion budget enforced",[]{std::wstring c=L"a0=j+j;";for(int i=1;i<=7;++i)c+=L"a"+std::to_wstring(i)+L"=a"+std::to_wstring(i-1)+L"+a"+std::to_wstring(i-1)+L";";refuse(c,L"a7²=",Reason::ExpansionLimit);});
 test("output budget enforced without truncation",[]{std::wstring e=L"a";for(int i=0;i<8;++i)e+=L"+a";refuse(L"a="+std::wstring(32,L'j'),e+L"=",Reason::OutputLimit);});
 test("numeric fast path ignores unrelated bad definitions",[]{ready(std::wstring(1025,L'a'),L"1/3+1/6=",L"1/2",Kind::Value);});
 test("numeric mixed postfix caret grammar preserved",[]{ready(L"a=b;b=a",L"2²^3=",L"64",Kind::Value);});
 test("symbolic mixed postfix caret retains power grouping",[]{ready(L"a=j",L"a²^3=",L"(j²)³");});
 test("alias chain resolves without inventing unknown target",[]{ready(L"a=b;b=j;c=1",L"a²+c²=",L"j²+1²");});
 test("subtraction right operand parentheses preserved",[]{ready(L"a=j-h",L"c-a=",L"c−(j−h)");});
 test("division right operand parentheses preserved",[]{ready(L"a=j*h",L"x/a=",L"x÷(j×h)");});
 test("finite decimal definitions never round",[]{ready(L"a=.1;b=.2",L"a+b=",L"3/10",Kind::Value);});
 test("overflow cannot bypass numeric budget through a variable",[]{arithmetic(L"a=9007199254740991+1",L"a+j=",wangshu::numeric::Reason::Integer);});
 test("negative integer exponent uses exact reciprocal",[]{ready(L"a=2",L"a^-3=",L"1/8",Kind::Value);});
 test("malformed Unicode input rejected",[]{refuse(L"a=j",std::wstring(1,static_cast<wchar_t>(0xd800))+L"=",Reason::InvalidUnicode);});
 test("prose-only line is irrelevant to explicit definitions",[]{ready(L"讲义 notes\na=j",L"a²=",L"j²");});
 test("exact context length boundary accepted",[]{ready(std::wstring(1020,L'x')+L"\na=j",L"a²=",L"j²");});
 test("exact expression length boundary accepted",[]{ready(L"a=j",std::wstring(126,L' ')+L"a=",L"j");});
 test("exact nesting limit accepted",[]{ready(L"a=j",std::wstring(24,L'(')+L"a"+std::wstring(24,L')')+L"=",L"j");});
 test("exact combined 128 token budget accepted",[]{std::wstring result=L"j";for(int i=1;i<32;++i)result+=L"+j";ready(L"a=j",balanced(32)+L"=",result);});
 test("exact 16 unique name budget accepted",[]{std::wstring c;for(int i=0;i<15;++i)c+=L"a"+std::to_wstring(i)+L"=j;";ready(c,L"a0+a14=",L"j+j");});
 test("exact identifier length accepted",[]{auto id=std::wstring(32,L'j');ready(L"a="+id,L"a=",id);});
 test("exact output budget accepted",[]{auto a=std::wstring(32,L'j'),b=std::wstring(25,L'h');std::wstring e=L"a",r=a;for(int i=1;i<7;++i){e+=L"+a";r+=L"+"+a;}e+=L"+b=";r+=L"+"+b;expect(r.size()==256);ready(L"a="+a+L";b="+b,e,r);});
 test("one unit over output budget rejected",[]{auto a=std::wstring(32,L'j'),b=std::wstring(26,L'h');std::wstring e=L"a";for(int i=1;i<7;++i)e+=L"+a";refuse(L"a="+a+L";b="+b,e+L"+b=",Reason::OutputLimit);});
 test("zero to zero through variable still rejected",[]{arithmetic(L"a=0",L"a^0=",wangshu::numeric::Reason::ZeroToZero);});
 test("exact integer budget accepted through variable",[]{ready(L"a=9007199254740991",L"a=",L"9007199254740991",Kind::Value);});
 test("exact UTF16 context boundary with astral prose accepted",[]{std::wstring c;for(int i=0;i<510;++i)c+=L"\U0001F600";ready(c+L"\na=j",L"a²=",L"j²");});
 std::cout<<"RESULT passed="<<passed<<" failed="<<failed<<" total="<<passed+failed<<"; native Linux host only, Windows/TSF/PE NOT_RUN\n";return failed?1:0;
}
