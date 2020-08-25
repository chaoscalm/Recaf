package me.coley.recaf.parse.bytecode;

import me.coley.recaf.config.ConfAssembler;
import me.coley.analysis.value.AbstractValue;
import me.coley.recaf.parse.bytecode.ast.*;
import me.coley.recaf.parse.bytecode.exception.ASTParseException;
import me.coley.recaf.parse.bytecode.exception.AssemblerException;
import me.coley.recaf.parse.bytecode.exception.VerifierException;
import me.coley.recaf.util.AccessFlag;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.Collection;
import java.util.Collections;

/**
 * Bytecode assembler for methods.
 *
 * @author Matt
 */
public class MethodAssembler {
	private final String declaringType;
	private final ConfAssembler config;
	private Collection<LocalVariableNode> defaultVariables = Collections.emptySet();
	private MethodNode lastCompile;
	private Frame<AbstractValue>[] frames;
	private MethodCompilation compilation;

	/**
	 * @param declaringType
	 * 		Internal name of class declaring the method to be assembled.
	 * @param config
	 * 		Assembler config.
	 */
	public MethodAssembler(String declaringType, ConfAssembler config) {
		this.declaringType = declaringType;
		this.config = config;
	}

	/**
	 * @param result
	 * 		AST parse result.
	 *
	 * @return Generated {@link MethodNode}.
	 *
	 * @throws AssemblerException
	 * 		<ul>
	 * 		<li>When the given AST contains errors</li>
	 * 		<li>When the given AST is missing a definition</li>
	 * 		</ul>
	 */
	public MethodNode compile(ParseResult<RootAST> result) throws AssemblerException {
		if(!result.isSuccess()) {
			ASTParseException cause = result.getProblems().get(0);
			AssemblerException ex  = new AssemblerException(cause, "AST must not contain errors", cause.getLine());
			ex.addSubExceptions(result.getProblems());
			throw ex;
		}
		RootAST root = result.getRoot();
		// Get definition
		MethodDefinitionAST definition = root.search(MethodDefinitionAST.class).stream().findFirst().orElse(null);
		if (definition == null)
			throw new AssemblerException("AST must have definition statement");
		int access = definition.getModifierMask();
		String name = definition.getName().getName();
		String desc = definition.getDescriptor();
		String[] exceptions = toExceptions(root);
		SignatureAST signatureAST = root.search(SignatureAST.class).stream().findFirst().orElse(null);
		String signature = (signatureAST == null) ? null : signatureAST.getSignature();
		MethodNode node = new MethodNode(access, name, desc, signature, exceptions);
		// Check if method is abstract, do no further handling
		if(AccessFlag.isAbstract(access))
			return node;
		MethodCompilation compilation = this.compilation = new MethodCompilation(result, definition, node);
		// Create label mappings
		root.search(LabelAST.class).forEach(lbl -> {
			LabelNode generated = new LabelNode();
			compilation.assignLabel(generated, lbl);
		});
		// Parse try-catches
		for(TryCatchAST tc : root.search(TryCatchAST.class)) {
			LabelNode start = compilation.getLabel(tc.getLblStart().getName());
			if(start == null)
				throw new AssemblerException("No label associated with try-catch start: " +
						tc.getLblStart().getName(), tc.getLine());
			LabelNode end = compilation.getLabel(tc.getLblEnd().getName());
			if(end == null)
				throw new AssemblerException("No label associated with try-catch end: " +
						tc.getLblEnd().getName(), tc.getLine());
			LabelNode handler = compilation.getLabel(tc.getLblHandler().getName());
			if(handler == null)
				throw new AssemblerException("No label associated with try-catch handler: " +
						tc.getLblHandler().getName(), tc.getLine());
			String type = tc.getType().getType();
			node.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, type));
		}
		// Parse variables (name to index)
		Variables variables = new Variables(AccessFlag.isStatic(access), declaringType);
		if (defaultVariables != null)
			variables.populateDefaults(defaultVariables);
		variables.visit(root);
		compilation.setVariables(variables);
		// Parse instructions
		node.instructions = new InsnList();
		for(AST ast : root.getChildren()) {
			AbstractInsnNode insn;
			if(ast instanceof LabelAST)
				insn = compilation.getLabel(((LabelAST) ast).getName().getName());
			else if(ast instanceof AliasAST)
				continue;
			else if(ast instanceof Instruction)
				insn = ((Instruction) ast).compile(compilation);
			else
				continue;
			node.instructions.add(insn);
			compilation.assignInstruction(insn, ast);
		}
		// Set stack size (dummy) and max local count.
		node.maxStack = 0xFF;
		node.maxLocals = variables.getMax();
		// Verify code is valid & store analyzed stack data.
		// Use the saved data to fill in missing variable types.
		if (config.verify) {
			frames = verify(node);
			variables.visitWithFrames(frames, compilation.getNameToLabel());
		}
		if (config.variables) {
			node.localVariables = variables.getVariables(compilation.getNameToLabel());
		}
		return (lastCompile = node);
	}

	/**
	 * Verify the generated method.
	 *
	 * @param generated
	 * 		Method generated by this assembler.
	 *
	 * @return Analyzed frames of the method.
	 *
	 * @throws VerifierException
	 * 		Wrapped verification exception.
	 */
	private Frame<AbstractValue>[] verify(MethodNode generated) throws VerifierException {
		return new MethodVerifier(this, declaringType).verify(generated);
	}

	/**
	 * @param defaultVariables
	 * 		Map of existing variables to use as a baseline.
	 */
	public void setDefaultVariables(Collection<LocalVariableNode> defaultVariables) {
		this.defaultVariables = defaultVariables;
	}

	/**
	 * @return Analyzed frames. Will be {@code null} if analysis failed.
	 */
	public Frame<AbstractValue>[] getFrames() {
		return frames;
	}

	/**
	 * @param insn
	 * 		Generated instruction.
	 *
	 * @return Line instruction was generated from.
	 */
	public int getLine(AbstractInsnNode insn) {
		MethodCompilation compilation = this.compilation;
		if (compilation == null)
			return -1;
		return compilation.getLine(insn);
	}

	/**
	 * @param line
	 * 		Line number.
	 *
	 * @return Instruction at line.
	 */
	public AbstractInsnNode getInsn(int line) {
		MethodCompilation compilation = this.compilation;
		if (compilation == null)
			return null;
		return compilation.getInsn(line);
	}

	/**
	 * @return Last compiled method.
	 */
	public MethodNode getLastCompile() {
		return lastCompile;
	}

	/**
	 * @return Declaring type of method.
	 */
	public String getDeclaringType() {
		return declaringType;
	}

	/**
	 * @return compilation context.
	 */
	public MethodCompilation getCompilation() {
		return compilation;
	}

	/**
	 * @param root
	 * 		AST of method.
	 *
	 * @return All thrown types.
	 */
	private static String[] toExceptions(RootAST root) {
		return root.search(ThrowsAST.class).stream()
				.map(ast -> ast.getType().getType())
				.toArray(String[]::new);
	}
}
