#pragma once

#include "local_predictions.h"

namespace wangshu::numeric_suffix {

inline std::u16string_view trimLeadingSpace(std::u16string_view text) {
    const auto first = text.find_first_not_of(u" \t");
    return first == std::u16string_view::npos ? std::u16string_view{} : text.substr(first);
}

inline bool take(std::u16string_view& text, char16_t expected) {
    if (text.empty() || text.front() != expected) return false;
    text.remove_prefix(1);
    return true;
}

inline int component(std::u16string_view& text, size_t minDigits, size_t maxDigits, int low, int high) {
    size_t digits = 0;
    int value = 0;
    while (!text.empty() && text.front() >= u'0' && text.front() <= u'9') {
        if (++digits > maxDigits) return -1;
        value = value * 10 + text.front() - u'0';
        text.remove_prefix(1);
    }
    return digits >= minDigits && value >= low && value <= high ? value : -1;
}

inline std::u16string_view date(std::u16string_view text, bool iso) {
    const int year = component(text, 4, 4, 1, 9999);
    if (year < 0) return {};
    if (text.empty()) return iso ? u"-" : u"年";
    if (!take(text, iso ? u'-' : u'年')) return {};
    const int month = component(text, iso ? 2 : 1, 2, 1, 12);
    if (month < 0) return {};
    if (text.empty()) return iso ? u"-" : u"月";
    if (iso || !take(text, u'月')) return {};
    constexpr int monthDays[] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    const bool leapYear = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    const int maxDay = monthDays[month - 1] + (month == 2 && leapYear ? 1 : 0);
    return component(text, 1, 2, 1, maxDay) >= 0 && text.empty() ? u"日" : u"";
}

inline std::u16string_view time(std::u16string_view text, bool english) {
    if (component(text, english ? 2 : 1, 2, 0, 23) < 0) return {};
    if (text.empty()) return english ? u":" : u"时";
    if (english || !take(text, u'时') || component(text, 1, 2, 0, 59) < 0) return {};
    if (text.empty()) return u"分";
    if (!take(text, u'分')) return {};
    return component(text, 1, 2, 0, 59) >= 0 && text.empty() ? u"秒" : u"";
}

inline std::u16string_view suggest(std::u16string_view before, std::u16string_view after) {
    if (before.size() > 32768 || after.size() > 32768 ||
        (!after.empty() && !local_prediction::separator(after.front()))) return {};
    const auto newline = before.find_last_of(u"\r\n");
    auto line = before.substr(newline == std::u16string_view::npos ? 0 : newline + 1);
    if (line.size() > 64) return {};
    line = trimLeadingSpace(line);
    const auto separator = line.find_first_of(u":：");
    if (separator == std::u16string_view::npos) return {};
    const auto label = line.substr(0, separator);
    const auto value = trimLeadingSpace(line.substr(separator + 1));
    std::u16string_view suffix;
    if (label == u"日期" || local_prediction::equalFold(label, u"ISO date"))
        suffix = date(value, label != u"日期");
    else if (label == u"时间" || local_prediction::equalFold(label, u"time"))
        suffix = time(value, label != u"时间");
    return local_prediction::duplicate(suffix, after) ? std::u16string_view{} : suffix;
}

}
