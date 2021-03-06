/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jasper.compiler;

import org.apache.jasper.JasperException;
import org.apache.jasper.compiler.ELNode.ELText;
import org.apache.jasper.compiler.ELNode.Function;
import org.apache.jasper.compiler.ELNode.Root;
import org.apache.jasper.compiler.ELNode.Text;

/**
 * This class implements a parser for EL expressions.
 * 
 * It takes strings of the form xxx${..}yyy${..}zzz etc, and turn it into a
 * ELNode.Nodes.
 * 
 * Currently, it only handles text outside ${..} and functions in ${ ..}.
 * 
 * @author Kin-man Chung
 */

public class ELParser {

    private Token curToken;  // current token
    private Token prevToken; // previous token

    private ELNode.Nodes expr;

    private ELNode.Nodes ELexpr;

    private int index; // Current index of the expression

    private String expression; // The EL expression
    
    private char type;

    private boolean escapeBS; // is '\' an escape char in text outside EL?

    private final boolean isDeferredSyntaxAllowedAsLiteral;

    private static final String reservedWords[] = { "and", "div", "empty",
            "eq", "false", "ge", "gt", "instanceof", "le", "lt", "mod", "ne",
            "not", "null", "or", "true" };

    public ELParser(String expression, boolean isDeferredSyntaxAllowedAsLiteral) {
        index = 0;
        this.expression = expression;
        this.isDeferredSyntaxAllowedAsLiteral = isDeferredSyntaxAllowedAsLiteral;
        expr = new ELNode.Nodes();
    }

    /**
     * Parse an EL expression
     * 
     * @param expression
     *            The input expression string of the form Char* ('${' Char*
     *            '}')* Char*
     * @param isDeferredSyntaxAllowedAsLiteral
     *                      Are deferred expressions treated as literals?
     * @return Parsed EL expression in ELNode.Nodes
     */
    public static ELNode.Nodes parse(String expression,
            boolean isDeferredSyntaxAllowedAsLiteral) {
        ELParser parser = new ELParser(expression,
                isDeferredSyntaxAllowedAsLiteral);
        while (parser.hasNextChar()) {
            String text = parser.skipUntilEL();
            if (text.length() > 0) {
                parser.expr.add(new ELNode.Text(text));
            }
            ELNode.Nodes elexpr = parser.parseEL();
            if (!elexpr.isEmpty()) {
                parser.expr.add(new ELNode.Root(elexpr, parser.type));
            }
        }
        return parser.expr;
    }

    /**
     * Parse an EL expression string '${...}'. Currently only separates the EL
     * into functions and everything else.
     * 
     * @return An ELNode.Nodes representing the EL expression
     * 
     * TODO: Can this be refactored to use the standard EL implementation?
     */
    private ELNode.Nodes parseEL() {

        StringBuilder buf = new StringBuilder();
        ELexpr = new ELNode.Nodes();
        while (hasNext()) {
            curToken = nextToken();
            if (curToken instanceof Char) {
                if (curToken.toChar() == '}') {
                    break;
                }
                buf.append(curToken.toChar());
            } else {
                // Output whatever is in buffer
                if (buf.length() > 0) {
                    ELexpr.add(new ELNode.ELText(buf.toString()));
                    buf = new StringBuilder();
                }
                if (!parseFunction()) {
                    ELexpr.add(new ELNode.ELText(curToken.toString()));
                }
            }
        }
        if (buf.length() > 0) {
            ELexpr.add(new ELNode.ELText(buf.toString()));
        }

        return ELexpr;
    }

    /**
     * Parse for a function FunctionInvokation ::= (identifier ':')? identifier
     * '(' (Expression (,Expression)*)? ')' Note: currently we don't parse
     * arguments
     */
    private boolean parseFunction() {
        if (!(curToken instanceof Id) || isELReserved(curToken.toString()) ||
                prevToken instanceof Char && prevToken.toChar() == '.') {
            return false;
        }
        String s1 = null; // Function prefix
        String s2 = curToken.toString(); // Function name
        if (hasNext()) {
            int mark = getIndex();
            curToken = nextToken();
            if (curToken.toChar() == ':') {
                if (hasNext()) {
                    Token t2 = nextToken();
                    if (t2 instanceof Id) {
                        s1 = s2;
                        s2 = t2.toString();
                        if (hasNext()) {
                            curToken = nextToken();
                        }
                    }
                }
            }
            if (curToken.toChar() == '(') {
                ELexpr.add(new ELNode.Function(s1, s2));
                return true;
            }
            curToken = prevToken;
            setIndex(mark);
        }
        return false;
    }

    /**
     * Test if an id is a reserved word in EL
     */
    private boolean isELReserved(String id) {
        int i = 0;
        int j = reservedWords.length;
        while (i < j) {
            int k = (i + j) / 2;
            int result = reservedWords[k].compareTo(id);
            if (result == 0) {
                return true;
            }
            if (result < 0) {
                i = k + 1;
            } else {
                j = k;
            }
        }
        return false;
    }

    /**
     * Skip until an EL expression ('${' || '#{') is reached, allowing escape
     * sequences '\\' and '\$' and '\#'.
     * 
     * @return The text string up to the EL expression
     */
    private String skipUntilEL() {
        char prev = 0;
        StringBuilder buf = new StringBuilder();
        while (hasNextChar()) {
            char ch = nextChar();
            if (prev == '\\') {
                prev = 0;
                if (ch == '\\') {
                    buf.append('\\');
                    if (!escapeBS)
                        prev = '\\';
                } else if (ch == '$'
                        || (!isDeferredSyntaxAllowedAsLiteral && ch == '#')) {
                    buf.append(ch);
                }
                // else error!
            } else if (prev == '$'
                    || (!isDeferredSyntaxAllowedAsLiteral && prev == '#')) {
                if (ch == '{') {
                    this.type = prev;
                    prev = 0;
                    break;
                }
                buf.append(prev);
                prev = 0;
            }
            if (ch == '\\' || ch == '$'
                    || (!isDeferredSyntaxAllowedAsLiteral && ch == '#')) {
                prev = ch;
            } else {
                buf.append(ch);
            }
        }
        if (prev != 0) {
            buf.append(prev);
        }
        return buf.toString();
    }

    /*
     * @return true if there is something left in EL expression buffer other
     * than white spaces.
     */
    private boolean hasNext() {
        skipSpaces();
        return hasNextChar();
    }

    /*
     * @return The next token in the EL expression buffer.
     */
    private Token nextToken() {
        prevToken = curToken;
        skipSpaces();
        if (hasNextChar()) {
            char ch = nextChar();
            if (Character.isJavaIdentifierStart(ch)) {
                StringBuilder buf = new StringBuilder();
                buf.append(ch);
                while ((ch = peekChar()) != -1
                        && Character.isJavaIdentifierPart(ch)) {
                    buf.append(ch);
                    nextChar();
                }
                return new Id(buf.toString());
            }

            if (ch == '\'' || ch == '"') {
                return parseQuotedChars(ch);
            } else {
                // For now...
                return new Char(ch);
            }
        }
        return null;
    }

    /*
     * Parse a string in single or double quotes, allowing for escape sequences
     * '\\', and ('\"', or "\'")
     */
    private Token parseQuotedChars(char quote) {
        StringBuilder buf = new StringBuilder();
        buf.append(quote);
        while (hasNextChar()) {
            char ch = nextChar();
            if (ch == '\\') {
                ch = nextChar();
                if (ch == '\\' || ch == quote) {
                    buf.append(ch);
                }
                // else error!
            } else if (ch == quote) {
                buf.append(ch);
                break;
            } else {
                buf.append(ch);
            }
        }
        return new QuotedString(buf.toString());
    }

    /*
     * A collection of low level parse methods dealing with character in the EL
     * expression buffer.
     */

    private void skipSpaces() {
        while (hasNextChar()) {
            if (expression.charAt(index) > ' ')
                break;
            index++;
        }
    }

    private boolean hasNextChar() {
        return index < expression.length();
    }

    private char nextChar() {
        if (index >= expression.length()) {
            return (char) -1;
        }
        return expression.charAt(index++);
    }

    private char peekChar() {
        if (index >= expression.length()) {
            return (char) -1;
        }
        return expression.charAt(index);
    }

    private int getIndex() {
        return index;
    }

    private void setIndex(int i) {
        index = i;
    }

    /*
     * Represents a token in EL expression string
     */
    private static class Token {

        char toChar() {
            return 0;
        }

        @Override
        public String toString() {
            return "";
        }
    }

    /*
     * Represents an ID token in EL
     */
    private static class Id extends Token {
        String id;

        Id(String id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /*
     * Represents a character token in EL
     */
    private static class Char extends Token {

        private char ch;

        Char(char ch) {
            this.ch = ch;
        }

        @Override
        char toChar() {
            return ch;
        }

        @Override
        public String toString() {
            return (new Character(ch)).toString();
        }
    }

    /*
     * Represents a quoted (single or double) string token in EL
     */
    private static class QuotedString extends Token {

        private String value;

        QuotedString(String v) {
            this.value = v;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public char getType() {
        return type;
    }


    protected static class TextBuilder extends ELNode.Visitor {

        protected StringBuilder output = new StringBuilder();

        public String getText() {
            return output.toString();
        }

        @Override
        public void visit(Root n) throws JasperException {
            output.append(n.getType());
            output.append('{');
            n.getExpression().visit(this);
            output.append('}');
        }

        @Override
        public void visit(Function n) throws JasperException {
            if (n.getPrefix() != null) {
                output.append(n.getPrefix());
                output.append(':');
            }
            output.append(n.getName());
            output.append('(');
        }

        @Override
        public void visit(Text n) throws JasperException {
            output.append(n.getText());
        }

        @Override
        public void visit(ELText n) throws JasperException {
            output.append(n.getText());
        }
    }
}
