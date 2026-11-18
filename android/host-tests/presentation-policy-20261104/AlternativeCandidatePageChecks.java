package org.wangshu.ime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Direct production pager, with in-memory candidate providers. */
final class AlternativeCandidatePageChecks {
    private static final String[] EMPTY = new String[0];
    private static final String[] EXACT = {
        "a","exact","b","exact","c","exact","d","exact","e","exact",
        "f","exact","g","exact","h","exact","i","exact"
    };
    private static int passed;

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        passed++;
        System.out.println("PASS " + label);
    }

    public static void main(String[] args) {
        List<Integer> exactReads = new ArrayList<>();
        List<String> variantReads = new ArrayList<>();
        CandidatePages pages = new CandidatePages(EMPTY, index -> {
            exactReads.add(index);
            if (index == 0) return EXACT;
            return new String[]{"j","exact"};
        }, List.of("one", "two"), key -> {
            variantReads.add(key);
            return key.equals("one") ? new String[]{"a","fuzzy","j","variant one","x","variant one"}
                    : new String[]{"x","duplicate","y","variant two"};
        });
        check(pages.hasAlternativeSpellings() && exactReads.isEmpty() && variantReads.isEmpty(),
                "availability performs no query");
        check(Arrays.equals(EXACT,pages.page(0)) && variantReads.isEmpty(),
                "exact first group is unchanged and does not query variants");
        String[] quick = pages.alternatives();
        check(Arrays.equals(quick,new String[]{"j","variant one","x","variant one","y","variant two"}),
                "alternatives deduplicate first exact group and each variant");
        check(exactReads.equals(List.of(0)) && variantReads.equals(List.of("one","two")),
                "explicit alternatives do not scan the exact tail");
        quick[0] = "tampered";
        check(pages.alternatives()[0].equals("j") && variantReads.size()==2,
                "reopening alternatives reuses results and returns a copy");
        check(Arrays.equals(pages.page(1),new String[]{"j","exact"}) && !pages.isFuzzyPage(1),
                "normal deeper exact candidates keep their original position and annotation");
        check(Arrays.equals(pages.page(2),new String[]{"x","variant one","y","variant two"})
                && pages.isFuzzyPage(2) && variantReads.size()==2,
                "normal fuzzy tail retains global exact dedup and reuses variant rows");
        CandidatePages off = new CandidatePages(EMPTY, index -> {
            throw new AssertionError("disabled alternatives should perform no source read");
        }, List.of(), null);
        check(!off.hasAlternativeSpellings() && off.alternatives().length==0,
                "disabled rules never populate alternatives");
        CandidatePages bounded = new CandidatePages(EMPTY, index -> EMPTY,
                List.of("one", "two"), key -> {
            if (!key.equals("one")) throw new AssertionError("extra variant after a full group");
            return EXACT;
        });
        check(Arrays.equals(bounded.alternatives(),EXACT),
                "even when exact results are empty, the alternative group stops at nine");
        System.out.println("TOTAL " + passed + " PASS; production pager with synthetic providers");
    }
}
