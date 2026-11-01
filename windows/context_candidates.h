#pragma once
#include "numeric_candidates.h"
#include <optional>
#include <string_view>
#include <map>
#include <set>
#include <memory>
#include <new>

// Original bounded context provider, no COM, persistence, external service or evaluator.
// Integration into Windows TSF is a separate acceptance step.
namespace wangshu::context_math {
constexpr size_t ContextLimit=1024,ExpressionLimit=128,TokenLimit=128,DepthLimit=24;
constexpr size_t VariableLimit=16,IdentifierLimit=32,ExpansionLimit=512,OutputLimit=256;
enum class Status {Ready,Unsupported,Ambiguous};
enum class Kind {Value,Substitution};
enum class Reason {None,ExpressionLimit,ContextLimit,InvalidUnicode,InvalidSyntax,InvalidAssignment,IdentifierLimit,VariableLimit,TokenLimit,DepthLimit,ExpansionLimit,OutputLimit,Conflict,Cycle,NoDefinedSubstitution,UnknownExponent,ArithmeticRejected,Allocation};
struct Candidate {
    std::wstring text;
    Kind kind=Kind::Value;
    const wchar_t* label()const{return kind==Kind::Value?L"精确结果":L"代入";}
};
struct Result {
    Status status=Status::Unsupported;
    Reason reason=Reason::None;
    numeric::Reason arithmetic=numeric::Reason::None;
    std::optional<Candidate> candidate;
};
namespace detail {
struct Refusal {Reason reason;Status status;};
[[noreturn]] inline void refuse(Reason reason,Status status=Status::Unsupported){throw Refusal{reason,status};}
inline bool letter(wchar_t c){return(c>=L'a'&&c<=L'z')||(c>=L'A'&&c<=L'Z');}
inline bool digit(wchar_t c){return c>=L'0'&&c<=L'9';}
inline bool space(wchar_t c){return c==L' '||c==L'\t';}
inline void depth(size_t n){if(n>DepthLimit)refuse(Reason::DepthLimit);}
// Count UTF16 consistently on both Linux wchar32 tests and Windows wchar16.
inline size_t units(std::wstring_view value,size_t limit,Reason limitReason){
    size_t count=0;
    for(size_t i=0;i<value.size();++i){
        unsigned c=static_cast<unsigned>(value[i]);
        if constexpr(sizeof(wchar_t)==2){
            if(c>=0xd800&&c<=0xdbff){
                if(i+1==value.size())refuse(Reason::InvalidUnicode);
                unsigned low=static_cast<unsigned>(value[++i]);
                if(low<0xdc00||low>0xdfff)refuse(Reason::InvalidUnicode);
                count+=2;
            }else{if(c>=0xdc00&&c<=0xdfff)refuse(Reason::InvalidUnicode);++count;}
        }else{
            if(c>0x10ffff||(c>=0xd800&&c<=0xdfff))refuse(Reason::InvalidUnicode);
            count+=c>0xffff?2:1;
        }
        if(count>limit)refuse(limitReason);
    }
    return count;
}
inline std::wstring joined(std::initializer_list<std::wstring_view> parts){
    size_t n=0;
    for(auto part:parts){n+=units(part,OutputLimit,Reason::OutputLimit);if(n>OutputLimit)refuse(Reason::OutputLimit);}
    std::wstring value;value.reserve(n);for(auto part:parts)value.append(part);return value;
}
struct Budget {
    size_t tokens=0,parsed=0,expanded=0;
    std::set<std::wstring> names;
    void token(){if(++tokens>TokenLimit)refuse(Reason::TokenLimit);}
    void name(std::wstring_view value){
        if(value.size()>IdentifierLimit)refuse(Reason::IdentifierLimit);
        names.emplace(value);if(names.size()>VariableLimit)refuse(Reason::VariableLimit);
    }
    void parsedNode(){if(++parsed>ExpansionLimit)refuse(Reason::ExpansionLimit);}
    void expandedNode(){if(++expanded>ExpansionLimit)refuse(Reason::ExpansionLimit);}
};
enum class Operation {Number,Name,Positive,Negative,Add,Subtract,Multiply,Divide,Power};
struct Node;
using Tree=std::shared_ptr<const Node>;
struct Node {
    Operation op=Operation::Number;
    numeric::Rational value;
    std::wstring name;
    Tree left,right;
};
inline Tree number(Budget& budget,numeric::Rational value){budget.parsedNode();auto node=std::make_shared<Node>();node->value=value;return node;}
inline Tree name(Budget& budget,const std::wstring& value){budget.parsedNode();auto node=std::make_shared<Node>();node->op=Operation::Name;node->name=value;return node;}
inline Tree operation(Budget& budget,Operation op,Tree left,Tree right){budget.parsedNode();auto node=std::make_shared<Node>();node->op=op;node->left=std::move(left);node->right=std::move(right);return node;}
struct Token {std::wstring text;size_t start,end;};
class Parser {
    std::vector<Token> tokens;
    size_t cursor=0;
    Budget& budget;
    Tree primary(size_t nesting){
        depth(nesting);Tree value;
        if(take(L"(")){value=expression(nesting+1);if(!take(L")"))refuse(Reason::InvalidSyntax);}
        else{
            if(done())refuse(Reason::InvalidSyntax);
            const auto text=tokens[cursor++].text;
            if(letter(text[0]))value=name(budget,text);
            else if(digit(text[0])||text[0]==L'.'){numeric::Parser parser(text);value=number(budget,parser.parse());}
            else refuse(Reason::InvalidSyntax);
        }
        if(take(L"²"))value=operation(budget,Operation::Power,value,number(budget,numeric::Rational(2)));
        else if(take(L"³"))value=operation(budget,Operation::Power,value,number(budget,numeric::Rational(3)));
        if(peek()==L"²"||peek()==L"³")refuse(Reason::InvalidSyntax);
        return value;
    }
    Tree power(size_t nesting){auto value=primary(nesting);if(take(L"^"))value=operation(budget,Operation::Power,value,unary(nesting+1));return value;}
    Tree unary(size_t nesting){depth(nesting);if(take(L"+"))return operation(budget,Operation::Positive,{},unary(nesting+1));if(take(L"-"))return operation(budget,Operation::Negative,{},unary(nesting+1));return power(nesting);}
    Tree product(size_t nesting){auto value=unary(nesting);for(;;){if(take(L"*"))value=operation(budget,Operation::Multiply,value,unary(nesting));else if(take(L"/"))value=operation(budget,Operation::Divide,value,unary(nesting));else return value;}}
public:
    Parser(std::wstring_view source,Budget& shared):budget(shared){
        for(size_t i=0;i<source.size();){
            wchar_t c=source[i];if(space(c)){++i;continue;}size_t begin=i;
            if(letter(c)){++i;while(i<source.size()&&(letter(source[i])||digit(source[i])||source[i]==L'_'))++i;budget.name(source.substr(begin,i-begin));}
            else if(digit(c)||c==L'.'){++i;while(i<source.size()&&(digit(source[i])||source[i]==L'.'))++i;}
            else{
                ++i;if(c==L'−')c=L'-';if(c==L'×')c=L'*';if(c==L'÷')c=L'/';
                if(std::wstring_view(L"+-*/^()=,²³").find(c)==std::wstring_view::npos)refuse(Reason::InvalidSyntax);
            }
            budget.token();
            if(i==begin+1&&!letter(source[begin])&&!digit(source[begin])&&source[begin]!=L'.')tokens.push_back({std::wstring(1,c),begin,i});
            else tokens.push_back({std::wstring(source.substr(begin,i-begin)),begin,i});
        }
    }
    std::wstring_view peek()const{return done()?std::wstring_view{}:std::wstring_view(tokens[cursor].text);}
    bool done()const{return cursor==tokens.size();}
    bool take(std::wstring_view value){if(peek()!=value)return false;++cursor;return true;}
    Tree expression(size_t nesting){depth(nesting);auto value=product(nesting);for(;;){if(take(L"+"))value=operation(budget,Operation::Add,value,product(nesting));else if(take(L"-"))value=operation(budget,Operation::Subtract,value,product(nesting));else return value;}}
    void requireEnd()const{if(!done())refuse(Reason::InvalidSyntax);}
    bool assignmentAhead()const{return cursor+1<tokens.size()&&letter(tokens[cursor].text[0])&&tokens[cursor+1].text==L"=";}
    std::wstring takeAssignmentName(){if(!assignmentAhead())refuse(Reason::InvalidAssignment);auto result=tokens[cursor].text;cursor+=2;return result;}
    bool gapBefore()const{return cursor>0&&cursor<tokens.size()&&tokens[cursor-1].end<tokens[cursor].start;}
};
inline bool same(const Tree& a,const Tree& b,size_t nesting=0){
    depth(nesting);if(!a||!b)return a==b;
    return a->op==b->op&&a->value.numerator==b->value.numerator&&a->value.denominator==b->value.denominator&&a->name==b->name&&same(a->left,b->left,nesting+1)&&same(a->right,b->right,nesting+1);
}
using Definitions=std::map<std::wstring,Tree>;
inline Definitions definitions(std::wstring_view context,Budget& budget){
    Definitions result;size_t from=0;
    for(size_t i=0;i<=context.size();++i){
        if(i<context.size()&&context[i]!=L';'&&context[i]!=L'；'&&context[i]!=L'\r'&&context[i]!=L'\n')continue;
        auto line=context.substr(from,i-from);from=i+1;if(line.find(L'=')==std::wstring_view::npos)continue;
        Parser parser(line,budget);
        while(!parser.done()){
            auto key=parser.takeAssignmentName();auto value=parser.expression(0);auto found=result.find(key);
            if(found!=result.end()&&!same(found->second,value))refuse(Reason::Conflict,Status::Ambiguous);
            result[key]=std::move(value);
            if(parser.done())break;
            if(parser.take(L",")){if(!parser.assignmentAhead())refuse(Reason::InvalidAssignment);}
            else if(!parser.assignmentAhead()||!parser.gapBefore())refuse(Reason::InvalidAssignment);
        }
    }
    return result;
}
struct Resolver {
    const Definitions& definitions;
    Budget& budget;
    bool used=false;
    Tree resolve(const Tree& node,size_t nesting,std::set<std::wstring>& visiting){
        depth(nesting);budget.expandedNode();
        if(node->op==Operation::Name){
            auto found=definitions.find(node->name);
            if(found!=definitions.end()){
                used=true;if(!visiting.insert(node->name).second)refuse(Reason::Cycle,Status::Ambiguous);
                auto value=resolve(found->second,nesting+1,visiting);visiting.erase(node->name);return value;
            }
        }
        auto copy=std::make_shared<Node>(*node);
        if(node->left)copy->left=resolve(node->left,nesting+1,visiting);
        if(node->right)copy->right=resolve(node->right,nesting+1,visiting);
        return copy;
    }
};
inline std::optional<numeric::Rational> evaluate(const Tree& node,size_t nesting=0){
    depth(nesting);if(node->op==Operation::Number)return node->value;if(node->op==Operation::Name)return {};
    auto right=evaluate(node->right,nesting+1);
    if(!node->left){if(!right)return {};return node->op==Operation::Negative?right->negated():*right;}
    auto left=evaluate(node->left,nesting+1);
    if(node->op==Operation::Divide&&right&&right->numerator==0)throw numeric::Refusal{numeric::Reason::DivisionByZero};
    if(node->op==Operation::Power){
        if(!right)refuse(Reason::UnknownExponent);
        if(right->denominator!=1||numeric::magnitude(right->numerator)>numeric::ExponentLimit)throw numeric::Refusal{numeric::Reason::Exponent};
        return left?std::optional<numeric::Rational>(left->power(*right)):std::nullopt;
    }
    if(!left||!right)return {};
    switch(node->op){
        case Operation::Add:return left->plus(*right);
        case Operation::Subtract:return left->plus(right->negated());
        case Operation::Multiply:return left->times(*right);
        case Operation::Divide:return left->divided(*right);
        default:refuse(Reason::InvalidSyntax);
    }
}
inline int precedence(const Tree& node){
    switch(node->op){
        case Operation::Number:return node->value.denominator==1?5:2;
        case Operation::Name:return 5;
        case Operation::Positive:case Operation::Negative:return 3;
        case Operation::Power:return 4;
        case Operation::Multiply:case Operation::Divide:return 2;
        default:return 1;
    }
}
inline std::wstring render(const Tree&,size_t);
inline std::wstring child(const Tree& node,int parent,bool equal,size_t nesting){auto value=render(node,nesting);return precedence(node)<parent||(equal&&precedence(node)==parent)?joined({L"(",value,L")"}):value;}
inline std::wstring render(const Tree& node,size_t nesting=0){
    depth(nesting);
    if(node->op==Operation::Number)return node->value.text();
    if(node->op==Operation::Name)return node->name;
    if(!node->left){auto value=child(node->right,3,true,nesting+1);return joined({node->op==Operation::Negative?L"−":L"+",value});}
    if(node->op==Operation::Power){
        auto base=child(node->left,4,true,nesting+1);
        if(node->right->op==Operation::Number&&node->right->value.denominator==1){
            if(node->right->value.numerator==2)return joined({base,L"²"});
            if(node->right->value.numerator==3)return joined({base,L"³"});
        }
        auto exponent=render(node->right,nesting+1);return joined({base,L"^(",exponent,L")"});
    }
    int parent=precedence(node);bool protect=node->op==Operation::Subtract||node->op==Operation::Multiply||node->op==Operation::Divide;
    auto left=child(node->left,parent,false,nesting+1),right=child(node->right,parent,protect,nesting+1);
    const wchar_t* symbol=node->op==Operation::Add?L"+":node->op==Operation::Subtract?L"−":node->op==Operation::Multiply?L"×":L"÷";
    return joined({left,symbol,right});
}
} // detail

inline Result suggest(std::wstring_view context,std::wstring_view endingExpression) noexcept{
    try{
        detail::units(endingExpression,ExpressionLimit,Reason::ExpressionLimit);
        std::wstring expression(endingExpression);
        auto numericResult=numeric::suggest(expression);
        if(numericResult.ready)return {Status::Ready,Reason::None,numeric::Reason::None,Candidate{std::move(numericResult.text),Kind::Value}};
        bool hasName=false;for(auto c:endingExpression)if(detail::letter(c)){hasName=true;break;}
        if(!hasName)return {Status::Unsupported,Reason::ArithmeticRejected,numericResult.reason,{}};
        detail::units(context,ContextLimit,Reason::ContextLimit);
        size_t end=expression.find_last_not_of(L" \t\r\n");
        if(end==std::wstring::npos||expression[end]!=L'=')detail::refuse(Reason::InvalidSyntax);
        expression.resize(end);
        detail::Budget budget;detail::Parser parser(expression,budget);auto input=parser.expression(0);parser.requireEnd();
        auto definitions=detail::definitions(context,budget);detail::Resolver resolver{definitions,budget};
        // Validate all definitions, including unused conflicts/cycles/invalid arithmetic.
        for(const auto& item:definitions){std::set<std::wstring> visiting{item.first};auto expanded=resolver.resolve(item.second,0,visiting);detail::evaluate(expanded);}
        resolver.used=false;std::set<std::wstring> visiting;auto expanded=resolver.resolve(input,0,visiting);auto value=detail::evaluate(expanded);
        if(value)return {Status::Ready,Reason::None,numeric::Reason::None,Candidate{value->text(),Kind::Value}};
        if(!resolver.used)detail::refuse(Reason::NoDefinedSubstitution);
        auto text=detail::render(expanded);detail::units(text,OutputLimit,Reason::OutputLimit);
        return {Status::Ready,Reason::None,numeric::Reason::None,Candidate{std::move(text),Kind::Substitution}};
    }catch(const detail::Refusal& error){return {error.status,error.reason,numeric::Reason::None,{}};}
    catch(const numeric::Refusal& error){return {Status::Unsupported,Reason::ArithmeticRejected,error.reason,{}};}
    catch(const std::bad_alloc&){return {Status::Unsupported,Reason::Allocation,numeric::Reason::None,{}};}
    catch(...){return {Status::Unsupported,Reason::InvalidSyntax,numeric::Reason::None,{}};}
}
} // wangshu::context_math
