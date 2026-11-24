#pragma once

#include <cstdint>
#include <limits>
#include <regex>
#include <string_view>

namespace wangshu::voice_format {

enum class Mode : unsigned { Raw, Math, Numbers };

inline int digit(wchar_t value) {
    if (value>=L'0'&&value<=L'9') return value-L'0';
    if (value==L'〇') return 0;
    if (value==L'两') return 2;
    const auto index=std::wstring_view(L"零一二三四五六七八九").find(value);
    return index==std::wstring_view::npos?-1:static_cast<int>(index);
}

inline std::wstring digits(std::wstring_view text) {
    std::wstring result;
    result.reserve(text.size());
    for (auto value:text) result.push_back(static_cast<wchar_t>(L'0'+digit(value)));
    return result;
}

inline bool asciiDigits(std::wstring_view text) {
    return text.find_first_not_of(L"0123456789")==std::wstring_view::npos;
}

inline int section(std::wstring_view text,bool afterLargeUnit) {
    if (afterLargeUnit&&text.size()>1&&digit(text.front())==0) text.remove_prefix(1);
    if (text.empty()) return -1;
    int result=0,pending=-1,previousUnit=10000;
    bool gap=false;
    for (size_t index=0;index<text.size();++index) {
        const auto character=text[index];
        const int number=digit(character);
        if (number>=0) {
            if (pending>=0) {
                if (text.size()>4||!asciiDigits(text)) return -1;
                int arabic=0;
                for (auto value:text) arabic=arabic*10+digit(value);
                return arabic;
            }
            if (number==0&&result!=0) {
                if (gap||index+1==text.size()) return -1;
                gap=true;
            } else pending=number;
            continue;
        }
        const int unit=character==L'千'?1000:character==L'百'?100:character==L'十'?10:0;
        if (!unit||unit>=previousUnit) return -1;
        if (index==0&&unit==10) pending=1;
        if (pending<=0||(result!=0&&((unit*10!=previousUnit)!=gap))) return -1;
        result+=pending*unit;
        previousUnit=unit;
        pending=-1;
        gap=false;
    }
    if (pending<0) return result;
    if (result!=0&&((previousUnit>10)!=gap)) return -1;
    return result+pending;
}

inline std::int64_t integer(std::wstring_view text) {
    if (text.empty()) return -1;
    std::int64_t total=0,previousUnit=(std::numeric_limits<std::int64_t>::max)();
    size_t start=0;
    for (size_t index=0;index<text.size();++index) {
        const auto unit=text[index]==L'亿'?100000000:text[index]==L'万'?10000:0;
        if (!unit) continue;
        if (unit>=previousUnit) return -1;
        const auto group=section(text.substr(start,index-start),start!=0);
        if (group<=0) return -1;
        total+=static_cast<std::int64_t>(group)*unit;
        previousUnit=unit;
        start=index+1;
    }
    if (start==text.size()) return total;
    const auto tail=section(text.substr(start),start!=0);
    if (start&&(tail<=0||(tail<previousUnit/10&&digit(text[start])!=0))) return -1;
    return tail<0?-1:total+tail;
}

template<class Convert>
inline std::wstring rewrite(const std::wstring& text,const std::wregex& pattern,Convert convert) {
    std::wstring result;
    size_t copied=0;
    for (std::wsregex_iterator it(text.begin(),text.end(),pattern),end;it!=end;++it) {
        const auto start=static_cast<size_t>(it->position());
        result.append(text,copied,start-copied);
        result+=convert(*it,start?text[start-1]:L'\0');
        copied=start+it->length();
    }
    result.append(text,copied,std::wstring::npos);
    return result;
}

inline bool numberCharacter(wchar_t value) {
    return digit(value)>=0||std::wstring_view(L"十百千万亿点负").find(value)!=std::wstring_view::npos;
}

inline bool embeddedQuantity(std::wstring_view text, size_t start, bool spokenMinus) {
    if (!start) return false;
    const auto previous = text[start - 1];
    if (spokenMinus && (previous == L'+' || previous == L'-')) return true;
    if (start < 2 || std::wstring_view(L"@_/\\#=+-?&%").find(previous) == std::wstring_view::npos)
        return false;
    const auto prefix = text[start - 2];
    return (prefix >= L'A' && prefix <= L'Z') || (prefix >= L'a' && prefix <= L'z') ||
           (prefix >= L'0' && prefix <= L'9') || prefix == L'_';
}

// Same explicit contexts as Android VoiceNumberFormat. Uncertain forms and
// existing punctuation are preserved.
inline std::wstring numbers(const std::wstring& text) {
    if (text.size()>8192) return text;
    static const std::wstring digitClass=L"0-9零〇一二三四五六七八九";
    static const std::wstring integerClass=digitClass+L"两十百千万亿";
    static const std::wregex date(L"(["+digitClass+L"]{4})年(["+integerClass+L"]{1,3})月(["+integerClass+L"]{1,3})([日号])");
    // Java's default regex whitespace is ASCII. The wide CRT class can
    // also include ideographic spaces, so state the accepted set explicitly.
    static const std::wstring space=LR"([ \t\n\x0B\f\r]*)";
    static const std::wregex identifier(L"((?:验证码|邮编|编号|序列号)(?:是|为|[：:])"+space+
        L")(["+digitClass+L"]{1,20})(?="+space+L"(?:[。！？!?，,；;]|$))");
    static const std::wregex quantity(L"(负?["+integerClass+L"]{1,20}?)(?:点(["+digitClass+L"]{1,12}))?(?=摄氏度|度|公斤|千克|克|公里|厘米|毫米|米|毫升|升|小时|个|件|元)");
    auto dates=rewrite(text,date,[](const std::wsmatch& match,wchar_t previous) {
        if (numberCharacter(previous)) return match.str();
        const int year=std::stoi(digits(match[1].str()));
        const auto month=integer(match[2].str()),day=integer(match[3].str());
        constexpr int days[]={31,28,31,30,31,30,31,31,30,31,30,31};
        if (!year||month<1||month>12||day<1) return match.str();
        const bool leap=year%4==0&&(year%100!=0||year%400==0);
        if (day>days[month-1]+(month==2&&leap?1:0)) return match.str();
        return std::to_wstring(year)+L"年"+std::to_wstring(month)+L"月"+std::to_wstring(day)+match[4].str();
    });
    auto ids=rewrite(dates,identifier,[](const std::wsmatch& match,wchar_t) {
        return match[1].str()+digits(match[2].str());
    });
    return rewrite(ids,quantity,[&ids](const std::wsmatch& match,wchar_t previous) {
        if (numberCharacter(previous)||previous==L'.'||(previous>=L'A'&&previous<=L'Z')||
            (previous>=L'a'&&previous<=L'z')) return match.str();
        const auto whole=match[1].str();
        if (!match[2].matched&&asciiDigits(whole)) return match.str();
        const bool negative=whole.front()==L'负';
        if (embeddedQuantity(ids, static_cast<size_t>(match.position()), negative)) return match.str();
        const auto value=integer(std::wstring_view(whole).substr(negative?1:0));
        if (value<0||(!value&&!match[2].matched&&!negative)) return match.str();
        return (negative?L"-":L"")+std::to_wstring(value)+
               (match[2].matched?L"."+digits(match[2].str()):L"");
    });
}

}
