package main.visitor.type;

import com.sun.jdi.DoubleType;
import main.ast.nodes.Program;
import main.ast.nodes.declaration.*;
import main.ast.nodes.expression.*;
import main.ast.nodes.expression.operators.*;
import main.ast.nodes.expression.value.*;
import main.ast.nodes.expression.value.primitive.*;
import main.ast.nodes.statement.*;
import main.ast.type.*;
import main.ast.type.primitiveType.*;
import main.compileError.CompileError;
import main.compileError.typeErrors.*;
import main.symbolTable.SymbolTable;
import main.symbolTable.exceptions.*;
import main.symbolTable.item.*;
import main.visitor.Visitor;

import javax.swing.text.Element;
import java.util.*;

public class TypeChecker extends Visitor<Type> {
    public ArrayList<CompileError> typeErrors = new ArrayList<>();
    @Override
    public Type visit(Program program){
        SymbolTable.root = new SymbolTable();
        SymbolTable.top = new SymbolTable();
        for(FunctionDeclaration functionDeclaration : program.getFunctionDeclarations()){
            FunctionItem functionItem = new FunctionItem(functionDeclaration);
            try {
                SymbolTable.root.put(functionItem);
            }catch (ItemAlreadyExists ignored){}
        }
        for(PatternDeclaration patternDeclaration : program.getPatternDeclarations()){
            PatternItem patternItem = new PatternItem(patternDeclaration);
            try{
                SymbolTable.root.put(patternItem);
            }catch (ItemAlreadyExists ignored){}
        }
        program.getMain().accept(this);

        return null;
    }
    @Override
    public Type visit(FunctionDeclaration functionDeclaration){
        SymbolTable.push(new SymbolTable());
        try {
            FunctionItem functionItem = (FunctionItem) SymbolTable.root.getItem(FunctionItem.START_KEY +
                    functionDeclaration.getFunctionName().getName());
            ArrayList<Type> currentArgTypes = functionItem.getArgumentTypes();
            for (int i = 0; i < functionDeclaration.getArgs().size(); i++) {
                VarItem argItem = new VarItem(functionDeclaration.getArgs().get(i).getName());
                argItem.setType(currentArgTypes.get(i));
                try {
                    SymbolTable.top.put(argItem);
                }catch (ItemAlreadyExists ignored){}
            }
        }catch (ItemNotFound ignored){}
        Type return_type = new NoType();
        for(Statement statement : functionDeclaration.getBody()) {
            if (statement instanceof ReturnStatement) {
                return_type = statement.accept(this);
                break;
            }
            statement.accept(this);
        }
        //TODO:Figure out whether return types of functions are not the same.
        SymbolTable.pop();
        return return_type;
        //TODO:Return the inferred type of the function
    }
    @Override
    public Type visit(PatternDeclaration patternDeclaration){
        SymbolTable.push(new SymbolTable());
        try {
            PatternItem patternItem = (PatternItem) SymbolTable.root.getItem(PatternItem.START_KEY +
                    patternDeclaration.getPatternName().getName());
            VarItem varItem = new VarItem(patternDeclaration.getTargetVariable());
            varItem.setType(patternItem.getTargetVarType());
            try {
                SymbolTable.top.put(varItem);
            }catch (ItemAlreadyExists ignored){}
            for(Expression expression : patternDeclaration.getConditions()){
                if(!(expression.accept(this) instanceof BoolType)){
                    typeErrors.add(new ConditionIsNotBool(expression.getLine()));
                    SymbolTable.pop();
                    return new NoType();
                }
            }
        //TODO:1-figure out whether return expression of different cases in pattern are of the same type/2-return the inferred type
        }catch (ItemNotFound ignored){}


        SymbolTable.pop();
        return null;
    }
    @Override
    public Type visit(MainDeclaration mainDeclaration){
        //TODO:visit main
        SymbolTable.push(new SymbolTable());
        for (Statement statement : mainDeclaration.getBody())
            statement.accept(this);
        return null;
    }
    @Override
    public Type visit(AccessExpression accessExpression){
        if(accessExpression.isFunctionCall()){
            //TODO:function is called here.set the arguments type and visit its declaration
            try{
                FunctionItem func = (FunctionItem) SymbolTable.root.getItem(FunctionItem.START_KEY + ((Identifier)(accessExpression.getAccessedExpression())).getName());
                ArrayList<Type> args = new ArrayList<>();
                for(int i = 0; i < func.getFunctionDeclaration().getArgs().size(); i++){
                    if(accessExpression.getArguments().size() <= i){
                        if(func.getFunctionDeclaration().getArgs().get(i).getDefaultVal() != null)
                            args.add(func.getFunctionDeclaration().getArgs().get(i).getDefaultVal().accept(this));
                    }
                    else args.add(accessExpression.getArguments().get(i).accept(this));
                }
                func.setArgumentTypes(args);
                return func.getFunctionDeclaration().accept(this);
            }catch (ItemNotFound ignored){}
        }
        else{
            Type accessedType = accessExpression.getAccessedExpression().accept(this);
            if(!(accessedType instanceof StringType) && !(accessedType instanceof ListType)){
                typeErrors.add(new IsNotIndexable(accessExpression.getLine()));
                return new NoType();
            }
            //TODO:index of access list must be int
            for(Expression exp : accessExpression.getDimentionalAccess()){
                if(!(exp.accept(this) instanceof IntType)){
                    typeErrors.add(new IsNotIndexable(exp.getLine()));
                    return new NoType();
                }
            }
            if(accessedType instanceof StringType){
                return new StringType();
            }
            return ((ListType) accessedType).getType();
        }
        return null;
    }

    @Override
    public Type visit(ReturnStatement returnStatement){
        //TODO:Visit return statement.Note that return type of functions are specified here
        if(returnStatement.hasRetExpression()){
            return returnStatement.getReturnExp().accept(this);
        }
        return new NoType();
    }
    @Override
    public Type visit(ExpressionStatement expressionStatement){
        return expressionStatement.getExpression().accept(this);

    }
    @Override
    public Type visit(ForStatement forStatement){
        Type type = ((ListType)(forStatement.getRangeExpression().accept(this))).getType();
        if(type instanceof NoType)
            return null;
        SymbolTable.push(SymbolTable.top.copy());
        VarItem varItem = new VarItem(forStatement.getIteratorId());
        varItem.setType(type);
        try{
            SymbolTable.top.put(varItem);
        }catch (ItemAlreadyExists ignored){}

        for(Statement statement : forStatement.getLoopBodyStmts())
            statement.accept(this);
        SymbolTable.pop();
        return null;
    }
    @Override
    public Type visit(IfStatement ifStatement){
        SymbolTable.push(SymbolTable.top.copy());
        for(Expression expression : ifStatement.getConditions())
            if(!(expression.accept(this) instanceof BoolType))
                typeErrors.add(new ConditionIsNotBool(expression.getLine()));
        for(Statement statement : ifStatement.getThenBody())
            statement.accept(this);
        for(Statement statement : ifStatement.getElseBody())
            statement.accept(this);
        SymbolTable.pop();
        return new NoType();
    }
    @Override
    public Type visit(LoopDoStatement loopDoStatement){
        SymbolTable.push(SymbolTable.top.copy());
        for(Statement statement : loopDoStatement.getLoopBodyStmts())
            statement.accept(this);
        SymbolTable.pop();
        return new NoType();
    }
    @Override
    public Type visit(AssignStatement assignStatement){
        if(assignStatement.isAccessList()){
            // TODO:assignment to list
            Type index = assignStatement.getAccessListExpression().accept(this);
            if (!(index instanceof IntType)){
                typeErrors.add(new AccessIndexIsNotInt(assignStatement.getLine()));
                return new NoType();
            }
            Type exp_type = assignStatement.getAssignExpression().accept(this);
            try{
                Type var_type = ((VarItem) SymbolTable.top.getItem(VarItem.START_KEY + assignStatement.getAssignedId().getName())).getType();
                if (var_type instanceof FloatType && exp_type instanceof IntType){
                    return new NoType();
                }
                else if (!(var_type.sameType(exp_type))){
                    typeErrors.add(new UnsupportedOperandType(assignStatement.getLine(), assignStatement.toString()));
                    return new NoType();
                }
            }
            catch(ItemNotFound ignored){}
        }
        else{
            VarItem newVarItem = new VarItem(assignStatement.getAssignedId());
            // TODO:maybe new type for a variable
            Type exp_type = assignStatement.getAssignExpression().accept(this);
            AssignOperator op = assignStatement.getAssignOperator();
            if (op == AssignOperator.ASSIGN){
                newVarItem.setType(exp_type);
            }
            else{
                try{
                    VarItem var_item = (VarItem) SymbolTable.top.getItem(VarItem.START_KEY + assignStatement.getAssignedId().getName());
                    Type var_type = var_item.getType();
                    if (var_type.sameType(exp_type)){
                        newVarItem.setType(var_type);
                    }
                    else if (var_type instanceof IntType && exp_type instanceof FloatType){
                        newVarItem.setType(exp_type);
                    }
                    else if (var_type instanceof FloatType && exp_type instanceof IntType){
                        newVarItem.setType(var_type);
                    }
                    else{
                        typeErrors.add(new UnsupportedOperandType(assignStatement.getLine(), assignStatement.toString()));
                        return new NoType();
                    }
                }
                catch (ItemNotFound ignored){}
            }
            try {
                SymbolTable.top.put(newVarItem);
            }catch (ItemAlreadyExists ignored){}
        }
        return new NoType();
    }
    @Override
    public Type visit(BreakStatement breakStatement){
        for(Expression expression : breakStatement.getConditions())
            if(!((expression.accept(this)) instanceof BoolType))
                typeErrors.add(new ConditionIsNotBool(expression.getLine()));

        return null;
    }
    @Override
    public Type visit(NextStatement nextStatement){
        for(Expression expression : nextStatement.getConditions())
            if(!((expression.accept(this)) instanceof BoolType))
                typeErrors.add(new ConditionIsNotBool(expression.getLine()));

        return null;
    }
    @Override
    public Type visit(PushStatement pushStatement){
        //TODO:visit push statement
        if(pushStatement.getInitial().accept(this) instanceof StringType){
            if(!(pushStatement.getToBeAdded().accept(this) instanceof StringType))
                typeErrors.add(new PushArgumentsTypesMisMatch(pushStatement.getLine()));
        }
        else if(pushStatement.getInitial().accept(this) instanceof ListType){
            if(!(((ListType) pushStatement.getInitial().accept(this)).getType().sameType(pushStatement.getToBeAdded().accept(this))))
                typeErrors.add(new PushArgumentsTypesMisMatch(pushStatement.getLine()));
        }
        else typeErrors.add(new IsNotPushedable(pushStatement.getLine()));
        return new NoType();
    }
    @Override
    public Type visit(PutStatement putStatement){
        //TODO:visit putStatement
        putStatement.getExpression().accept(this);
        return new NoType();

    }
    @Override
    public Type visit(BoolValue boolValue){
        return new BoolType();
    }
    @Override
    public Type visit(IntValue intValue){
        return new IntType();
    }
    @Override
    public Type visit(FloatValue floatValue){
        return new FloatType();}
    @Override
    public Type visit(StringValue stringValue){
        return new StringType();
    }
    @Override
    public Type visit(ListValue listValue){
        // TODO:visit listValue
        ArrayList<Expression> elements = listValue.getElements();
        if (elements.isEmpty()){
            return new ListType(new NoType());
        }
        Type tmp = elements.getFirst().accept(this);
        for (Expression el: elements){
            if (!(tmp.sameType(el.accept(this)))){
                typeErrors.add(new ListElementsTypesMisMatch(listValue.getLine()));
                return new NoType();
            }
        }
        return new ListType(tmp);
    }
    @Override
    public Type visit(FunctionPointer functionPointer){
        return new FptrType(functionPointer.getId().getName());
    }
    @Override
    public Type visit(AppendExpression appendExpression){
        Type appendeeType = appendExpression.getAppendee().accept(this);
        if(appendeeType instanceof StringType){
            for (Expression exp : appendExpression.getAppendeds()){
                if (!(exp.accept(this) instanceof StringType)){
                    typeErrors.add(new AppendTypesMisMatch(appendExpression.getLine()));
                    return new NoType();
                }
            }
        }
        else if(appendeeType instanceof ListType){
            for (Expression exp : appendExpression.getAppendeds()){
                if (!(exp.accept(this).sameType(((ListType) appendeeType).getType()))){
                    typeErrors.add(new AppendTypesMisMatch(appendExpression.getLine()));
                    return new NoType();
                }
            }
        }
        else{
            typeErrors.add(new IsNotAppendable(appendExpression.getLine()));
            return new NoType();
        }
        return (appendeeType instanceof StringType) ? new StringType() : new ListType(((ListType) appendeeType).getType());
    }
    @Override
    public Type visit(BinaryExpression binaryExpression){
        //TODO:visit binary expression
        BinaryOperator op = binaryExpression.getOperator();
        Type first_type = binaryExpression.getFirstOperand().accept(this);
        Type second_type = binaryExpression.getSecondOperand().accept(this);

        if (op == BinaryOperator.DIVIDE || op == BinaryOperator.PLUS || op == BinaryOperator.MINUS || op == BinaryOperator.MULT){
//            if (!(first_type instanceof IntType || first_type instanceof FloatType || first_type instanceof NoType)){
//                typeErrors.add(new UnsupportedOperandType(binaryExpression.getLine(), op.toString()));
//                return new NoType();
//            }
            if (first_type instanceof IntType){
                if (second_type instanceof IntType || second_type instanceof NoType){
                    return new IntType();
                }
                else if (second_type instanceof FloatType){
                    return new FloatType();
                }
                else{
                    typeErrors.add(new NonSameOperands(binaryExpression.getLine(), op));
                    return new NoType();
                }
            }
            else if (first_type instanceof FloatType){
                if (second_type instanceof IntType || second_type instanceof FloatType || second_type instanceof NoType){
                    return new FloatType();
                }
                else{
                    typeErrors.add(new NonSameOperands(binaryExpression.getLine(), op));
                    return new NoType();
                }
            }
            else if (first_type instanceof NoType){
                if (second_type instanceof IntType) {
                    return new IntType();
                } else if (second_type instanceof FloatType) {
                    return new FloatType();
                }
                else {
                    typeErrors.add(new NonSameOperands(binaryExpression.getLine(), op));
                    return new NoType();
                }
            }
            else{
                typeErrors.add(new NonSameOperands(binaryExpression.getLine(), op));
            }
        }
        else if (op == BinaryOperator.EQUAL || op == BinaryOperator.NOT_EQUAL){
            if (first_type.sameType(second_type)){
                return new BoolType();
            }
            if ((first_type instanceof IntType && second_type instanceof FloatType) ||
                    (first_type instanceof FloatType && second_type instanceof IntType)){
                return new BoolType();
            }
            else {
                typeErrors.add(new NonSameOperands(binaryExpression.getLine(), op));
                return new NoType();
            }
        }
        else if (op == BinaryOperator.GREATER_EQUAL_THAN || op == BinaryOperator.GREATER_THAN || op == BinaryOperator.LESS_EQUAL_THAN || op == BinaryOperator.LESS_THAN){
            if (first_type instanceof IntType || first_type instanceof FloatType){
                if (second_type instanceof IntType || second_type instanceof FloatType){
                    return new BoolType();
                }
                else{
                    typeErrors.add(new NonSameOperands(binaryExpression.getLine(), op));
                    return new NoType();
                }
            }
            else{
                typeErrors.add(new UnsupportedOperandType(binaryExpression.getLine(), op.toString()));
                return new NoType();
            }
        }
        return null;
    }
    @Override
    public Type visit(UnaryExpression unaryExpression){
        //TODO:visit unaryExpression
        UnaryOperator op = unaryExpression.getOperator();
        Type type = unaryExpression.getExpression().accept(this);
        if(op == UnaryOperator.NOT){
            if(!(type instanceof BoolType) && !(type instanceof NoType)){
                typeErrors.add(new UnsupportedOperandType(unaryExpression.getLine(), op.toString()));
                return new NoType();
            }
            return (type instanceof BoolType) ? new BoolType() : new NoType();
        }
        else if(op == UnaryOperator.DEC || op == UnaryOperator.INC || op == UnaryOperator.MINUS){
            if(!(type instanceof IntType) && !(type instanceof FloatType) && !(type instanceof NoType)){
                typeErrors.add(new UnsupportedOperandType(unaryExpression.getLine(), op.toString()));
                return new NoType();
            }
            return (type instanceof IntType) ? new IntType() : (type instanceof FloatType) ? new FloatType() : new NoType();
        }
        return null;
    }
    @Override
    public Type visit(ChompStatement chompStatement){
        if (!(chompStatement.getChompExpression().accept(this) instanceof StringType)) {
            typeErrors.add(new ChompArgumentTypeMisMatch(chompStatement.getLine()));
            return new NoType();
        }
        return new StringType();
    }
    @Override
    public Type visit(ChopStatement chopStatement){
        if(!(chopStatement.getChopExpression().accept(this) instanceof StringType)) {
            typeErrors.add(new ChopArgumentTypeMisMatch(chopStatement.getLine()));
            return new NoType();
        }
        return new StringType();
    }
    @Override
    public Type visit(Identifier identifier){
        // TODO:visit Identifier
        try{
            return ((VarItem) SymbolTable.top.getItem(VarItem.START_KEY + identifier.getName())).getType();
        }catch (ItemNotFound ignored){}
        return new NoType();
    }
    @Override
    public Type visit(LenStatement lenStatement){
        //TODO:visit LenStatement.Be careful about the return type of LenStatement.
        Type lenExpType = lenStatement.getExpression().accept(this);
        if (!(lenExpType instanceof StringType) && !(lenExpType instanceof ListType)){
            typeErrors.add(new LenArgumentTypeMisMatch(lenStatement.getLine()));
            return new NoType();
        }
        return new IntType();
    }
    @Override
    public Type visit(MatchPatternStatement matchPatternStatement){
        try{
            PatternItem patternItem = (PatternItem)SymbolTable.root.getItem(PatternItem.START_KEY +
                    matchPatternStatement.getPatternId().getName());
            patternItem.setTargetVarType(matchPatternStatement.getMatchArgument().accept(this));
            return patternItem.getPatternDeclaration().accept(this);
        }catch (ItemNotFound ignored){}
        return new NoType();
    }
    @Override
    public Type visit(RangeExpression rangeExpression){
        RangeType rangeType = rangeExpression.getRangeType();
        if(rangeType.equals(RangeType.LIST)){
            // TODO --> mind that the lists are declared explicitly in the grammar in this node, so handle the errors
            Type type = rangeExpression.getRangeExpressions().getFirst().accept(this);
            for(Expression exp : rangeExpression.getRangeExpressions()){
                if(type.sameType(exp.accept(this))){
                    typeErrors.add((new ListElementsTypesMisMatch(rangeExpression.getLine())));
                    return new ListType(new NoType());
                }
            }
            return new ListType(type);
        }
        else if(rangeType.equals(RangeType.DOUBLE_DOT)){
            if(!(rangeExpression.getRangeExpressions().getFirst().accept(this) instanceof IntType) || !(rangeExpression.getRangeExpressions().getLast().accept(this) instanceof IntType)){
                typeErrors.add(new RangeValuesMisMatch(rangeExpression.getLine()));
                return new ListType(new NoType());
            }
            return new ListType(new IntType());
        }
        else if(rangeType.equals(RangeType.IDENTIFIER)){
            if(rangeExpression.getRangeExpressions().getFirst().accept(this) instanceof ListType){
               return rangeExpression.getRangeExpressions().getFirst().accept(this);
            }
            typeErrors.add(new IsNotIterable(rangeExpression.getLine()));
            return new ListType(new NoType());
        }
        return null;
    }
}
