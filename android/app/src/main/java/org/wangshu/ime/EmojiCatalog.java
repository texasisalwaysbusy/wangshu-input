package org.wangshu.ime;

import java.util.*;

/** Hand-selected Unicode sequences and original descriptions. No context capture or persistence. */
public final class EmojiCatalog {
    public enum Kind { EMOJI, KAOMOJI }
    public static final class Item {
        public final Kind kind;public final String category,text,description;
        private Item(Kind k,String c,String t,String d){kind=k;category=c;text=t;description=d;}
    }
    private static final LinkedHashMap<String,List<Item>> CATEGORIES=new LinkedHashMap<>();
    static {
        add(Kind.EMOJI,"笑脸",new String[][]{
            {"😀","开心"},{"😃","大笑"},{"😄","微笑"},{"😁","露齿笑"},{"😆","眯眼笑"},{"😅","苦笑"},{"😂","笑哭"},{"🤣","笑翻"},{"😊","害羞微笑"},{"🙂","平静微笑"},{"🙃","倒脸"},{"😉","眨眼"},{"😍","喜欢"},{"🥰","爱心笑脸"},{"😘","亲吻"},{"😋","美味"},{"😎","墨镜"},{"🤔","思考"},{"🤗","拥抱"},{"😴","睡觉"},{"😢","流泪"},{"😭","大哭"},{"😮","惊讶"},{"😌","放松"},{"🥳","庆祝"},{"😤","生气"},{"😷","口罩"},{"🤯","震惊"},{"🥺","恳求"},{"🫠","融化"}
        });
        add(Kind.EMOJI,"手势",new String[][]{
            {"👍","赞"},{"👍🏻","赞 浅肤色"},{"👍🏽","赞 中肤色"},{"👍🏿","赞 深肤色"},{"👎","不赞"},{"👏","鼓掌"},{"🙏","合掌"},{"👋","挥手"},{"🤝","握手"},{"✌️","胜利"},{"🤞","交叉手指"},{"👌","好"},{"🫶","双手比心"},{"💪","加油"},{"☝️","向上"},{"🖐️","张开手"},{"✍️","书写"},{"🤟","手势爱你"}
        });
        add(Kind.EMOJI,"人物",new String[][]{
            {"👩‍💻","女程序员"},{"👨‍💻","男程序员"},{"🧑‍💻","程序员"},{"👩🏽‍💻","程序员 中肤色"},{"👩‍🔬","女科学家"},{"👨‍🔬","男科学家"},{"🧑‍🎓","毕业生"},{"👩‍🏫","女教师"},{"🧑‍🏫","教师"},{"🧑‍🚀","宇航员"},{"👨‍👩‍👧","一家三口"},{"👨‍👩‍👧‍👦","一家四口"},{"👩‍👩‍👧","两位母亲和孩子"},{"👨‍👨‍👧","两位父亲和孩子"},{"🧑‍🤝‍🧑","牵手"},{"🧑‍🦽","坐轮椅的人"},{"👶","婴儿"},{"🧓","长者"}
        });
        add(Kind.EMOJI,"自然",new String[][]{
            {"🐱","猫"},{"🐶","狗"},{"🐼","熊猫"},{"🐰","兔子"},{"🦊","狐狸"},{"🐻","熊"},{"🐸","青蛙"},{"🐙","章鱼"},{"🦋","蝴蝶"},{"🌸","樱花"},{"🌻","向日葵"},{"🌱","幼苗"},{"🌳","树"},{"🍁","枫叶"},{"☀️","太阳"},{"🌙","月亮"},{"⭐","星星"},{"🌈","彩虹"},{"☁️","云"},{"🌧️","雨"},{"❄️","雪花"},{"🔥","火"},{"💧","水滴"},{"🌊","海浪"}
        });
        add(Kind.EMOJI,"饮食",new String[][]{
            {"🍎","苹果"},{"🍊","橙子"},{"🍉","西瓜"},{"🍓","草莓"},{"🍇","葡萄"},{"🍌","香蕉"},{"🥑","牛油果"},{"🥕","胡萝卜"},{"🍚","米饭"},{"🍜","面条"},{"🥟","饺子"},{"🍣","寿司"},{"🍕","披萨"},{"🍞","面包"},{"🎂","蛋糕"},{"🍪","饼干"},{"☕","咖啡"},{"🍵","茶"},{"🧋","奶茶"},{"🥛","牛奶"}
        });
        add(Kind.EMOJI,"活动",new String[][]{
            {"🎉","庆祝"},{"🎈","气球"},{"🎁","礼物"},{"🎓","毕业"},{"📚","书本"},{"📖","打开的书"},{"✏️","铅笔"},{"📝","笔记"},{"💻","电脑"},{"📱","手机"},{"🔬","显微镜"},{"🔭","望远镜"},{"🧪","试管"},{"🧮","算盘"},{"⚽","足球"},{"🏀","篮球"},{"🎵","音乐"},{"🎨","绘画"},{"🧩","拼图"},{"🎮","游戏"}
        });
        add(Kind.EMOJI,"符号",new String[][]{
            {"❤️","红心"},{"🧡","橙心"},{"💛","黄心"},{"💚","绿心"},{"💙","蓝心"},{"💜","紫心"},{"🤍","白心"},{"🖤","黑心"},{"💔","心碎"},{"✨","闪光"},{"💡","灵感"},{"✅","完成"},{"❌","错误"},{"❗","感叹"},{"❓","疑问"},{"⚠️","注意"},{"♾️","无穷"},{"➕","加"},{"➖","减"},{"✖️","乘"},{"➗","除"},{"🔒","锁"},{"🔓","开锁"},{"💯","满分"}
        });
        add(Kind.EMOJI,"旗帜",new String[][]{
            {"🇨🇦","加拿大"},{"🇨🇳","中国"},{"🇺🇸","美国"},{"🇬🇧","英国"},{"🇯🇵","日本"},{"🇫🇷","法国"},{"🇩🇪","德国"},{"🇮🇳","印度"},{"🇦🇺","澳大利亚"},{"🇧🇷","巴西"},{"🇺🇳","联合国"},{"🏳️‍🌈","彩虹旗"},{"🏳️‍⚧️","跨性别旗"},{"🏴‍☠️","海盗旗"}
        });
        add(Kind.KAOMOJI,"颜文字",new String[][]{
            {"( 'ω' )","猫咪"},{"(｡•̀ᴗ-)✧","眨眼"},{"(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧","闪亮"},{"(≧▽≦)","开心"},{"( ´ ▽ ` )ﾉ","挥手"},{"(｡♥‿♥｡)","喜欢"},{"(づ｡◕‿‿◕｡)づ","拥抱"},{"(╥﹏╥)","哭泣"},{"(；′⌒`)","难过"},{"(￣▽￣)","轻松"},{"(⊙_⊙)","惊讶"},{"(・_・;)","困惑"},{"(ง •̀_•́)ง","加油"},{"(￣︶￣)","满足"},{"(ᵔᴥᵔ)","小熊"},{"(=^･ω･^=)","猫脸"},{"¯\\_(ツ)_/¯","耸肩"},{"(っ˘ω˘ς )","睡觉"},{"(｡•́︿•̀｡)","委屈"},{"(*^▽^*)","笑脸"},{"(－‸ლ)","无奈"},{"( •̀ ω •́ )✧","认真"},{"ヾ(•ω•`)o","挥挥"},{"(>_<)","皱眉"}
        });
    }
    private static void add(Kind kind,String category,String[][] values){
        if(CATEGORIES.containsKey(category))throw new IllegalStateException("duplicate category");ArrayList<Item> items=new ArrayList<>();
        for(String[] value:values){if(value.length!=2||value[0].isEmpty()||value[1].isEmpty())throw new IllegalStateException("bad catalogue item");items.add(new Item(kind,category,value[0],value[1]));}CATEGORIES.put(category,Collections.unmodifiableList(items));
    }
    public static List<String> categories(Kind kind){if(kind==null)return Collections.emptyList();ArrayList<String> out=new ArrayList<>();for(Map.Entry<String,List<Item>> e:CATEGORIES.entrySet())if(!e.getValue().isEmpty()&&e.getValue().get(0).kind==kind)out.add(e.getKey());return Collections.unmodifiableList(out);}
    public static List<Item> category(String name){List<Item> items=CATEGORIES.get(name);return items==null?Collections.emptyList():items;}
    /** Search only authored descriptions or exact glyphs, never editor context or history. */
    public static List<Item> search(String query,Kind kind){if(query==null||kind==null||query.length()>40)return Collections.emptyList();String needle=query.trim().toLowerCase(Locale.ROOT);if(needle.isEmpty())return Collections.emptyList();ArrayList<Item> out=new ArrayList<>();for(List<Item> group:CATEGORIES.values())for(Item item:group)if(item.kind==kind&&(item.description.toLowerCase(Locale.ROOT).contains(needle)||item.text.contains(needle))){out.add(item);if(out.size()==48)return Collections.unmodifiableList(out);}return Collections.unmodifiableList(out);}
    private EmojiCatalog(){}
}
