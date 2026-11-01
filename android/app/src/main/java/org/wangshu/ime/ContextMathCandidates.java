package org.wangshu.ime;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic, local, bounded suggestion provider. Never evaluates code or commits editor text. */
public final class ContextMathCandidates {
    public static final int MAX_CONTEXT_UTF16=1024;
    public static final int MAX_EXPRESSION_UTF16=128;
    public static final int MAX_TOKENS=128;
    public static final int MAX_DEPTH=24;
    public static final int MAX_VARIABLES=16;
    public static final int MAX_EXPONENT_ABS=16;
    public static final int MAX_INTEGER_BITS=2048;
    public static final int MAX_EXPANDED_NODES=512;
    public static final int MAX_OUTPUT_UTF16=1024;
    public static final int MAX_IDENTIFIER_UTF16=32;

    public enum Status { READY, UNSUPPORTED, AMBIGUOUS }
    public enum Kind { VALUE, SUBSTITUTION }
    public enum Reason {
        NONE, NULL_INPUT, CONTEXT_TOO_LONG, EXPRESSION_TOO_LONG, ENDING_EQUALS_REQUIRED,
        UNSUPPORTED_CHARACTER, INVALID_NUMBER, INVALID_SYNTAX, INVALID_ASSIGNMENT,
        TOKEN_LIMIT, DEPTH_LIMIT, VARIABLE_LIMIT, IDENTIFIER_LIMIT, INTEGER_LIMIT,
        EXPANSION_LIMIT, OUTPUT_LIMIT, DIVISION_BY_ZERO, ZERO_TO_ZERO,
        NON_INTEGER_EXPONENT, EXPONENT_LIMIT, UNKNOWN_EXPONENT,
        CYCLIC_DEFINITION, CONFLICTING_DEFINITION, NO_DEFINED_SUBSTITUTION
    }
    public static final class Candidate {
        public final String text,label;
        public final Kind kind;
        private Candidate(String text,String label,Kind kind){this.text=text;this.label=label;this.kind=kind;}
    }
    public static final class Result {
        public final Status status;
        /** Non-null only for READY. No input text is copied into a failure result. */
        public final Candidate candidate;
        public final Reason reason;
        private Result(Status status,Candidate candidate,Reason reason){this.status=status;this.candidate=candidate;this.reason=reason;}
    }
    private ContextMathCandidates(){}

    public static Result suggest(String context,String endingExpression){
        try{
            if(context==null||endingExpression==null)throw unsupported(Reason.NULL_INPUT);
            if(context.length()>MAX_CONTEXT_UTF16)throw unsupported(Reason.CONTEXT_TOO_LONG);
            if(endingExpression.length()>MAX_EXPRESSION_UTF16)throw unsupported(Reason.EXPRESSION_TOO_LONG);
            String expression=endingExpression.trim();
            if(expression.length()<2||expression.charAt(expression.length()-1)!='=')throw unsupported(Reason.ENDING_EQUALS_REQUIRED);
            Budget budget=new Budget();
            Parser parser=new Parser(expression.substring(0,expression.length()-1),budget);
            Node input=parser.expression(0);parser.requireEnd();
            Map<String,Node> definitions=definitions(context,budget);
            Resolver resolver=new Resolver(definitions);
            // Validate every explicit definition, including unused definitions and cycles.
            for(String variable:definitions.keySet())evaluate(resolver.definition(variable,0,new HashSet<>()),0);
            resolver.used=false;resolver.nodes=0;
            Node expanded=resolver.resolve(input,0,new HashSet<>());
            Rational value=evaluate(expanded,0);
            if(value!=null)return ready(value.toString(),"精确结果",Kind.VALUE);
            if(!resolver.used)throw unsupported(Reason.NO_DEFINED_SUBSTITUTION);
            return ready(render(expanded),"代入",Kind.SUBSTITUTION);
        }catch(Refusal refusal){return new Result(refusal.status,null,refusal.reason);}
    }
    private static Result ready(String text,String label,Kind kind){
        if(text.length()>MAX_OUTPUT_UTF16)throw unsupported(Reason.OUTPUT_LIMIT);
        return new Result(Status.READY,new Candidate(text,label,kind),Reason.NONE);
    }
    private static final class Refusal extends RuntimeException {
        private static final long serialVersionUID=1L;
        final Status status;final Reason reason;
        Refusal(Status status,Reason reason){super(reason.name(),null,false,false);this.status=status;this.reason=reason;}
    }
    private static Refusal unsupported(Reason reason){return new Refusal(Status.UNSUPPORTED,reason);}
    private static Refusal ambiguous(Reason reason){return new Refusal(Status.AMBIGUOUS,reason);}
    private static void depth(int value){if(value>MAX_DEPTH)throw unsupported(Reason.DEPTH_LIMIT);}
    private static boolean letter(char c){return c>='A'&&c<='Z'||c>='a'&&c<='z';}
    private static boolean digit(char c){return c>='0'&&c<='9';}
    private static final class Budget {
        int tokens;final Set<String> variables=new HashSet<>();
        void token(){if(++tokens>MAX_TOKENS)throw unsupported(Reason.TOKEN_LIMIT);}
        void variable(String name){if(name.length()>MAX_IDENTIFIER_UTF16)throw unsupported(Reason.IDENTIFIER_LIMIT);variables.add(name);if(variables.size()>MAX_VARIABLES)throw unsupported(Reason.VARIABLE_LIMIT);}
    }
    private static final class Token {
        final String value;final int start,end;
        Token(String value,int start,int end){this.value=value;this.start=start;this.end=end;}
    }
    private static final class Node {
        final char operation;final Rational value;final String variable;final Node left,right;
        private Node(char operation,Rational value,String variable,Node left,Node right){this.operation=operation;this.value=value;this.variable=variable;this.left=left;this.right=right;}
        static Node number(Rational value){return new Node('n',value,null,null,null);}
        static Node variable(String value){return new Node('v',null,value,null,null);}
        static Node unary(char operation,Node child){return new Node(operation,null,null,null,child);}
        static Node binary(char operation,Node left,Node right){return new Node(operation,null,null,left,right);}
    }
    private static final class Parser {
        final String source;final List<Token> tokens=new ArrayList<>();int cursor;
        Parser(String source,Budget budget){
            this.source=source;
            for(int i=0;i<source.length();){
                char c=source.charAt(i);if(Character.isWhitespace(c)){i++;continue;}
                int begin=i;String token;
                if(letter(c)){
                    i++;while(i<source.length()&&(letter(source.charAt(i))||digit(source.charAt(i))||source.charAt(i)=='_'))i++;
                    token=source.substring(begin,i);budget.variable(token);
                }else if(digit(c)||c=='.'){
                    boolean point=false;int digits=0;
                    while(i<source.length()&&(digit(source.charAt(i))||source.charAt(i)=='.')){if(source.charAt(i)=='.'){if(point)throw unsupported(Reason.INVALID_NUMBER);point=true;}else digits++;i++;}
                    token=source.substring(begin,i);if(digits==0||token.endsWith("."))throw unsupported(Reason.INVALID_NUMBER);
                }else{
                    i++;switch(c){
                        case '−':c='-';break;case '×':c='*';break;case '÷':c='/';break;
                        default:break;
                    }
                    if("+-*/^()=,²³".indexOf(c)<0)throw unsupported(Reason.UNSUPPORTED_CHARACTER);
                    token=String.valueOf(c);
                }
                budget.token();tokens.add(new Token(token,begin,i));
            }
        }
        String peek(){return cursor<tokens.size()?tokens.get(cursor).value:"";}
        boolean take(String token){if(peek().equals(token)){cursor++;return true;}return false;}
        void requireEnd(){if(cursor!=tokens.size())throw unsupported(Reason.INVALID_SYNTAX);}
        Node expression(int nesting){depth(nesting);Node value=product(nesting);while(peek().equals("+")||peek().equals("-")){char op=peek().charAt(0);cursor++;value=Node.binary(op,value,product(nesting));}return value;}
        Node product(int nesting){Node value=unary(nesting);while(peek().equals("*")||peek().equals("/")){char op=peek().charAt(0);cursor++;value=Node.binary(op,value,unary(nesting));}return value;}
        Node unary(int nesting){depth(nesting);if(take("+"))return Node.unary('p',unary(nesting+1));if(take("-"))return Node.unary('m',unary(nesting+1));return power(nesting);}
        Node power(int nesting){Node value=primary(nesting);if(take("²"))value=Node.binary('^',value,Node.number(Rational.integer(2)));else if(take("³"))value=Node.binary('^',value,Node.number(Rational.integer(3)));else if(take("^"))value=Node.binary('^',value,unary(nesting+1));return value;}
        Node primary(int nesting){
            depth(nesting);if(take("(")){Node value=expression(nesting+1);if(!take(")"))throw unsupported(Reason.INVALID_SYNTAX);return value;}
            String token=peek();if(token.isEmpty())throw unsupported(Reason.INVALID_SYNTAX);cursor++;
            if(letter(token.charAt(0)))return Node.variable(token);
            if(digit(token.charAt(0))||token.charAt(0)=='.')return Node.number(Rational.parse(token));
            throw unsupported(Reason.INVALID_SYNTAX);
        }
        boolean assignmentAhead(){return cursor+1<tokens.size()&&letter(peek().isEmpty()?' ':peek().charAt(0))&&tokens.get(cursor+1).value.equals("=");}
    }
    private static Map<String,Node> definitions(String context,Budget budget){
        Map<String,Node> result=new LinkedHashMap<>();
        // Prose-only lines are irrelevant. A line containing '=' must consist ONLY of explicit assignments.
        for(String line:context.split("[;\\r\\n]",-1)){
            if(line.indexOf('=')<0)continue;
            Parser parser=new Parser(line,budget);
            while(parser.cursor<parser.tokens.size()){
                if(!parser.assignmentAhead())throw unsupported(Reason.INVALID_ASSIGNMENT);
                String name=parser.peek();parser.cursor+=2;Node value=parser.expression(0);
                Node previous=result.get(name);
                if(previous!=null&&!render(previous).equals(render(value)))throw ambiguous(Reason.CONFLICTING_DEFINITION);
                result.put(name,value);
                if(parser.cursor==parser.tokens.size())break;
                if(parser.take(",")){if(!parser.assignmentAhead())throw unsupported(Reason.INVALID_ASSIGNMENT);}
                else if(parser.assignmentAhead()){
                    Token last=parser.tokens.get(parser.cursor-1),next=parser.tokens.get(parser.cursor);
                    if(next.start==last.end)throw unsupported(Reason.INVALID_ASSIGNMENT);
                }else throw unsupported(Reason.INVALID_ASSIGNMENT);
            }
        }
        return result;
    }
    private static final class Resolver {
        final Map<String,Node> definitions;int nodes;boolean used;
        Resolver(Map<String,Node> definitions){this.definitions=definitions;}
        Node definition(String name,int nesting,Set<String> visiting){
            if(!visiting.add(name))throw ambiguous(Reason.CYCLIC_DEFINITION);
            Node value=resolve(definitions.get(name),nesting+1,visiting);visiting.remove(name);return value;
        }
        Node resolve(Node node,int nesting,Set<String> visiting){
            depth(nesting);if(++nodes>MAX_EXPANDED_NODES)throw unsupported(Reason.EXPANSION_LIMIT);
            if(node.operation=='v'&&definitions.containsKey(node.variable)){used=true;return definition(node.variable,nesting,visiting);}
            if(node.operation=='v'||node.operation=='n')return node;
            return node.left==null?Node.unary(node.operation,resolve(node.right,nesting+1,visiting)):Node.binary(node.operation,resolve(node.left,nesting+1,visiting),resolve(node.right,nesting+1,visiting));
        }
    }
    private static Rational evaluate(Node node,int nesting){
        depth(nesting);if(node.operation=='n')return node.value;if(node.operation=='v')return null;
        Rational right=evaluate(node.right,nesting+1);
        if(node.left==null)return right==null?null:node.operation=='m'?right.negate():right;
        Rational left=evaluate(node.left,nesting+1);
        if(node.operation=='/'&&right!=null&&right.numerator.signum()==0)throw unsupported(Reason.DIVISION_BY_ZERO);
        if(node.operation=='^'){
            if(right==null)throw unsupported(Reason.UNKNOWN_EXPONENT);
            if(!right.denominator.equals(BigInteger.ONE))throw unsupported(Reason.NON_INTEGER_EXPONENT);
            if(right.numerator.abs().compareTo(BigInteger.valueOf(MAX_EXPONENT_ABS))>0)throw unsupported(Reason.EXPONENT_LIMIT);
            return left==null?null:left.power(right.numerator.intValue());
        }
        if(left==null||right==null)return null;
        switch(node.operation){case '+':return left.add(right);case '-':return left.add(right.negate());case '*':return left.multiply(right);case '/':return left.divide(right);default:throw unsupported(Reason.INVALID_SYNTAX);}
    }
    private static final class Rational {
        final BigInteger numerator,denominator;
        Rational(BigInteger numerator,BigInteger denominator){
            if(denominator.signum()==0)throw unsupported(Reason.DIVISION_BY_ZERO);
            if(denominator.signum()<0){numerator=numerator.negate();denominator=denominator.negate();}
            BigInteger gcd=numerator.gcd(denominator);numerator=numerator.divide(gcd);denominator=denominator.divide(gcd);
            if(numerator.abs().bitLength()>MAX_INTEGER_BITS||denominator.bitLength()>MAX_INTEGER_BITS)throw unsupported(Reason.INTEGER_LIMIT);
            this.numerator=numerator;this.denominator=denominator;
        }
        static Rational integer(long value){return new Rational(BigInteger.valueOf(value),BigInteger.ONE);}
        static Rational parse(String value){int point=value.indexOf('.');if(point<0)return new Rational(new BigInteger(value),BigInteger.ONE);String digits=value.substring(0,point)+value.substring(point+1);return new Rational(new BigInteger(digits),BigInteger.TEN.pow(value.length()-point-1));}
        Rational add(Rational other){return new Rational(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),denominator.multiply(other.denominator));}
        Rational multiply(Rational other){return new Rational(numerator.multiply(other.numerator),denominator.multiply(other.denominator));}
        Rational divide(Rational other){return new Rational(numerator.multiply(other.denominator),denominator.multiply(other.numerator));}
        Rational negate(){return new Rational(numerator.negate(),denominator);}
        Rational power(int exponent){
            if(exponent==0){if(numerator.signum()==0)throw unsupported(Reason.ZERO_TO_ZERO);return integer(1);}
            Rational base=exponent<0?new Rational(denominator,numerator):this,result=integer(1);int n=Math.abs(exponent);
            while(n>0){if((n&1)!=0)result=result.multiply(base);n>>=1;if(n>0)base=base.multiply(base);}return result;
        }
        @Override public String toString(){return denominator.equals(BigInteger.ONE)?numerator.toString():numerator+"/"+denominator;}
    }
    private static int precedence(Node node){if(node.operation=='n')return node.value.denominator.equals(BigInteger.ONE)?5:2;if(node.operation=='v')return 5;if(node.left==null)return 3;if(node.operation=='^')return 4;return node.operation=='+'||node.operation=='-'?1:2;}
    private static String child(Node node,int parent,boolean equal){String text=render(node);return precedence(node)<parent||equal&&precedence(node)==parent?"("+text+")":text;}
    private static String render(Node node){
        if(node.operation=='n')return node.value.toString();if(node.operation=='v')return node.variable;
        if(node.left==null)return (node.operation=='m'?"−":"+")+child(node.right,3,false);
        if(node.operation=='^'){
            String base=child(node.left,4,true);
            if(node.right.operation=='n'&&node.right.value.denominator.equals(BigInteger.ONE)){
                BigInteger e=node.right.value.numerator;if(e.equals(BigInteger.valueOf(2)))return base+"²";if(e.equals(BigInteger.valueOf(3)))return base+"³";
            }
            return base+"^("+render(node.right)+")";
        }
        int priority=precedence(node);String symbol=node.operation=='-'?"−":node.operation=='*'?"×":node.operation=='/'?"÷":"+";
        return child(node.left,priority,false)+symbol+child(node.right,priority,node.operation=='-'||node.operation=='/'||node.operation=='*');
    }
}
