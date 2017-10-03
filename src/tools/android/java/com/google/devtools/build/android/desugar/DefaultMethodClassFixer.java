// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.android.desugar;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Fixer of classes that extend interfaces with default methods to declare any missing methods
 * explicitly and call the corresponding companion method generated by {@link InterfaceDesugaring}.
 */
public class DefaultMethodClassFixer extends ClassVisitor {

  private final ClassReaderFactory classpath;
  private final ClassReaderFactory bootclasspath;
  private final ClassLoader targetLoader;
  private final DependencyCollector depsCollector;
  private final HashSet<String> instanceMethods = new HashSet<>();

  private boolean isInterface;
  private String internalName;
  private ImmutableList<String> directInterfaces;
  private String superName;
  /** This method node caches <clinit>, and flushes out in {@code visitEnd()}; */
  private MethodNode clInitMethodNode;

  public DefaultMethodClassFixer(
      ClassVisitor dest,
      ClassReaderFactory classpath,
      DependencyCollector depsCollector,
      ClassReaderFactory bootclasspath,
      ClassLoader targetLoader) {
    super(Opcodes.ASM5, dest);
    this.classpath = classpath;
    this.bootclasspath = bootclasspath;
    this.targetLoader = targetLoader;
    this.depsCollector = depsCollector;
  }

  @Override
  public void visit(
      int version,
      int access,
      String name,
      String signature,
      String superName,
      String[] interfaces) {
    checkState(this.directInterfaces == null);
    isInterface = BitFlags.isSet(access, Opcodes.ACC_INTERFACE);
    internalName = name;
    checkArgument(
        superName != null || "java/lang/Object".equals(name), // ASM promises this
        "Type without superclass: %s",
        name);
    this.directInterfaces = ImmutableList.copyOf(interfaces);
    this.superName = superName;
    super.visit(version, access, name, signature, superName, interfaces);
  }

  @Override
  public void visitEnd() {
    if (!isInterface && defaultMethodsDefined(directInterfaces)) {
      // Inherited methods take precedence over default methods, so visit all superclasses and
      // figure out what methods they declare before stubbing in any missing default methods.
      recordInheritedMethods();
      stubMissingDefaultAndBridgeMethods();
      // Check whether there are interfaces with default methods and <clinit>. If yes, the following
      // method call will return a list of interface fields to access in the <clinit> to trigger
      // the initialization of these interfaces.
      ImmutableList<String> companionsToTriggerInterfaceClinit =
          collectOrderedCompanionsToTriggerInterfaceClinit(directInterfaces);
      if (!companionsToTriggerInterfaceClinit.isEmpty()) {
        if (clInitMethodNode == null) {
          clInitMethodNode = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        }
        desugarClinitToTriggerInterfaceInitializers(companionsToTriggerInterfaceClinit);
      }
    }
    if (clInitMethodNode != null && super.cv != null) { // Write <clinit> to the chained visitor.
      clInitMethodNode.accept(super.cv);
    }
    super.visitEnd();
  }

  private boolean isClinitAlreadyDesugared(
      ImmutableList<String> companionsToAccessToTriggerInterfaceClinit) {
    InsnList instructions = clInitMethodNode.instructions;
    if (instructions.size() <= companionsToAccessToTriggerInterfaceClinit.size()) {
      // The <clinit> must end with RETURN, so if the instruction count is less than or equal to
      // the companion class count, this <clinit> has not been desugared.
      return false;
    }
    Iterator<AbstractInsnNode> iterator = instructions.iterator();
    for (String companion : companionsToAccessToTriggerInterfaceClinit) {
      if (!iterator.hasNext()) {
        return false;
      }
      AbstractInsnNode first = iterator.next();
      if (!(first instanceof MethodInsnNode)) {
        return false;
      }
      MethodInsnNode methodInsnNode = (MethodInsnNode) first;
      if (methodInsnNode.getOpcode() != Opcodes.INVOKESTATIC
          || !methodInsnNode.owner.equals(companion)
          || !methodInsnNode.name.equals(
              InterfaceDesugaring.COMPANION_METHOD_TO_TRIGGER_INTERFACE_CLINIT_NAME)) {
        return false;
      }
      checkState(
          methodInsnNode.desc.equals(
              InterfaceDesugaring.COMPANION_METHOD_TO_TRIGGER_INTERFACE_CLINIT_DESC),
          "Inconsistent method desc: %s vs %s",
          methodInsnNode.desc,
          InterfaceDesugaring.COMPANION_METHOD_TO_TRIGGER_INTERFACE_CLINIT_DESC);

      if (!iterator.hasNext()) {
        return false;
      }
      AbstractInsnNode second = iterator.next();
      if (second.getOpcode() != Opcodes.POP) {
        return false;
      }
    }
    return true;
  }

  private void desugarClinitToTriggerInterfaceInitializers(
      ImmutableList<String> companionsToTriggerInterfaceClinit) {
    if (isClinitAlreadyDesugared(companionsToTriggerInterfaceClinit)) {
      return;
    }
    InsnList desugarInsts = new InsnList();
    for (String companionClass : companionsToTriggerInterfaceClinit) {
      desugarInsts.add(
          new MethodInsnNode(
              Opcodes.INVOKESTATIC,
              companionClass,
              InterfaceDesugaring.COMPANION_METHOD_TO_TRIGGER_INTERFACE_CLINIT_NAME,
              InterfaceDesugaring.COMPANION_METHOD_TO_TRIGGER_INTERFACE_CLINIT_DESC,
              false));
    }
    if (clInitMethodNode.instructions.size() == 0) {
      clInitMethodNode.instructions.insert(new InsnNode(Opcodes.RETURN));
    }
    clInitMethodNode.instructions.insertBefore(
        clInitMethodNode.instructions.getFirst(), desugarInsts);
  }

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String desc, String signature, String[] exceptions) {
    // Keep track of instance methods implemented in this class for later.
    if (!isInterface) {
      recordIfInstanceMethod(access, name, desc);
    }
    if ("<clinit>".equals(name)) {
      checkState(clInitMethodNode == null, "This class fixer has been used. ");
      clInitMethodNode = new MethodNode(access, name, desc, signature, exceptions);
      return clInitMethodNode;
    }
    return super.visitMethod(access, name, desc, signature, exceptions);
  }

  private void stubMissingDefaultAndBridgeMethods() {
    TreeSet<Class<?>> allInterfaces = new TreeSet<>(InterfaceComparator.INSTANCE);
    for (String direct : directInterfaces) {
      // Loading ensures all transitively implemented interfaces can be loaded, which is necessary
      // to produce correct default method stubs in all cases.  We could do without classloading but
      // it's convenient to rely on Class.isAssignableFrom to compute subtype relationships, and
      // we'd still have to insist that all transitively implemented interfaces can be loaded.
      // We don't load the visited class, however, in case it's a generated lambda class.
      Class<?> itf = loadFromInternal(direct);
      collectInterfaces(itf, allInterfaces);
    }

    Class<?> superclass = loadFromInternal(superName);
    for (Class<?> interfaceToVisit : allInterfaces) {
      // if J extends I, J is allowed to redefine I's default methods.  The comparator we used
      // above makes sure we visit J before I in that case so we can use J's definition.
      if (superclass != null && interfaceToVisit.isAssignableFrom(superclass)) {
        // superclass already implements this interface, so we must skip it.  The superclass will
        // be similarly rewritten or comes from the bootclasspath; either way we don't need to and
        // shouldn't stub default methods for this interface.
        continue;
      }
      stubMissingDefaultAndBridgeMethods(interfaceToVisit.getName().replace('.', '/'));
    }
  }

  private Class<?> loadFromInternal(String internalName) {
    try {
      return targetLoader.loadClass(internalName.replace('/', '.'));
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(
          "Couldn't load " + internalName + ", is the classpath complete?", e);
    }
  }

  private void collectInterfaces(Class<?> itf, Set<Class<?>> dest) {
    checkArgument(itf.isInterface());
    if (!dest.add(itf)) {
      return;
    }
    for (Class<?> implemented : itf.getInterfaces()) {
      collectInterfaces(implemented, dest);
    }
  }

  private void recordInheritedMethods() {
    InstanceMethodRecorder recorder = new InstanceMethodRecorder();
    String internalName = superName;
    while (internalName != null) {
      ClassReader bytecode = bootclasspath.readIfKnown(internalName);
      if (bytecode == null) {
        bytecode =
            checkNotNull(
                classpath.readIfKnown(internalName), "Superclass not found: %s", internalName);
      }
      bytecode.accept(recorder, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
      internalName = bytecode.getSuperName();
    }
  }

  private void recordIfInstanceMethod(int access, String name, String desc) {
    if (BitFlags.noneSet(access, Opcodes.ACC_STATIC)) {
      // Record all declared instance methods, including abstract, bridge, and native methods, as
      // they all take precedence over default methods.
      instanceMethods.add(name + ":" + desc);
    }
  }

  /**
   * Starting from the given interfaces, this method scans the interface hierarchy, finds the
   * interfaces that have default methods and <clinit>, and returns the companion class names of
   * these interfaces.
   *
   * <p>Note that the returned companion classes are ordered in the order of the interface
   * initialization, which is consistent with the JVM behavior. For example, "class A implements I1,
   * I2", the returned list would be [I1$$CC, I2$$CC], not [I2$$CC, I1$$CC].
   */
  private ImmutableList<String> collectOrderedCompanionsToTriggerInterfaceClinit(
      ImmutableList<String> interfaces) {
    ImmutableList.Builder<String> companionCollector = ImmutableList.builder();
    HashSet<String> visitedInterfaces = new HashSet<>();
    for (String anInterface : interfaces) {
      collectOrderedCompanionsToTriggerInterfaceClinit(
          anInterface, visitedInterfaces, companionCollector);
    }
    return companionCollector.build();
  }

  private void collectOrderedCompanionsToTriggerInterfaceClinit(
      String anInterface,
      HashSet<String> visitedInterfaces,
      ImmutableList.Builder<String> companionCollector) {
    if (!visitedInterfaces.add(anInterface)) {
      return;
    }
    ClassReader bytecode = classpath.readIfKnown(anInterface);
    if (bytecode == null || bootclasspath.isKnown(anInterface)) {
      return;
    }
    String[] parentInterfaces = bytecode.getInterfaces();
    if (parentInterfaces != null && parentInterfaces.length > 0) {
      for (String parentInterface : parentInterfaces) {
        collectOrderedCompanionsToTriggerInterfaceClinit(
            parentInterface, visitedInterfaces, companionCollector);
      }
    }
    InterfaceInitializationNecessityDetector necessityDetector =
        new InterfaceInitializationNecessityDetector(bytecode.getClassName());
    bytecode.accept(necessityDetector, ClassReader.SKIP_DEBUG);
    if (necessityDetector.needsToInitialize()) {
      // If we need to initialize this interface, we initialize its companion class, and its
      // companion class will initialize the interface then. This desigin decision is made to avoid
      // access issue, e.g., package-private interfaces.
      companionCollector.add(InterfaceDesugaring.getCompanionClassName(anInterface));
    }
  }

  /**
   * Recursively searches the given interfaces for default methods not implemented by this class
   * directly. If this method returns true we need to think about stubbing missing default methods.
   */
  private boolean defaultMethodsDefined(ImmutableList<String> interfaces) {
    for (String implemented : interfaces) {
      if (bootclasspath.isKnown(implemented)) {
        continue;
      }
      ClassReader bytecode = classpath.readIfKnown(implemented);
      if (bytecode == null) {
        // Interface isn't on the classpath, which indicates incomplete classpaths. Record missing
        // dependency so we can check it later.  If we don't check then we may get runtime failures
        // or wrong behavior from default methods that should've been stubbed in.
        // TODO(kmb): Print a warning so people can start fixing their deps?
        depsCollector.missingImplementedInterface(internalName, implemented);
      } else {
        // Class in classpath and bootclasspath is a bad idea but in any event, assume the
        // bootclasspath will take precedence like in a classloader.
        // We can skip code attributes as we just need to find default methods to stub.
        DefaultMethodFinder finder = new DefaultMethodFinder();
        bytecode.accept(finder, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        if (finder.foundDefaultMethods()) {
          return true;
        }
      }
    }
    return false;
  }

  /** Returns {@code true} for non-bridge default methods not in {@link #instanceMethods}. */
  private boolean shouldStubAsDefaultMethod(int access, String name, String desc) {
    // Ignore private methods, which technically aren't default methods and can only be called from
    // other methods defined in the interface.  This also ignores lambda body methods, which is fine
    // as we don't want or need to stub those.  Also ignore bridge methods as javac adds them to
    // concrete classes as needed anyway and we handle them separately for generated lambda classes.
    // Note that an exception is that, if a bridge method is for a default interface method, javac
    // will NOT generate the bridge method in the implementing class. So we need extra logic to
    // handle these bridge methods.
    return isNonBridgeDefaultMethod(access) && !instanceMethods.contains(name + ":" + desc);
  }

  private static boolean isNonBridgeDefaultMethod(int access) {
    return BitFlags.noneSet(
        access,
        Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_PRIVATE);
  }

  /**
   * Check whether an interface method is a bridge method for a default interface method. This type
   * of bridge methods is special, as they are not put in the implementing classes by javac.
   */
  private boolean shouldStubAsBridgeDefaultMethod(int access, String name, String desc) {
    return BitFlags.isSet(access, Opcodes.ACC_BRIDGE | Opcodes.ACC_PUBLIC)
        && BitFlags.noneSet(access, Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC)
        && !instanceMethods.contains(name + ":" + desc);
  }

  private void stubMissingDefaultAndBridgeMethods(String implemented) {
    if (bootclasspath.isKnown(implemented)) {
      // Default methods on the bootclasspath will be available at runtime, so just ignore them.
      return;
    }
    ClassReader bytecode =
        checkNotNull(
            classpath.readIfKnown(implemented),
            "Couldn't find interface %s implemented by %s",
            implemented,
            internalName);
    bytecode.accept(new DefaultMethodStubber(), ClassReader.SKIP_DEBUG);
  }

  /**
   * Visitor for interfaces that produces delegates in the class visited by the outer {@link
   * DefaultMethodClassFixer} for every default method encountered.
   */
  private class DefaultMethodStubber extends ClassVisitor {

    private String interfaceName;

    public DefaultMethodStubber() {
      super(Opcodes.ASM5);
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      checkArgument(BitFlags.isSet(access, Opcodes.ACC_INTERFACE));
      checkState(interfaceName == null);
      interfaceName = name;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String desc, String signature, String[] exceptions) {
      if (shouldStubAsDefaultMethod(access, name, desc)) {
        // Remember we stubbed this method in case it's also defined by subsequently visited
        // interfaces.  javac would force the method to be defined explicitly if there any two
        // definitions conflict, but see stubMissingDefaultMethods() for how we deal with default
        // methods redefined in interfaces extending another.
        recordIfInstanceMethod(access, name, desc);
        depsCollector.assumeCompanionClass(
            internalName, InterfaceDesugaring.getCompanionClassName(interfaceName));

        // Add this method to the class we're desugaring and stub in a body to call the default
        // implementation in the interface's companion class. ijar omits these methods when setting
        // ACC_SYNTHETIC modifier, so don't.
        // Signatures can be wrong, e.g., when type variables are introduced, instantiated, or
        // refined in the class we're processing, so drop them.
        MethodVisitor stubMethod =
            DefaultMethodClassFixer.this.visitMethod(access, name, desc, (String) null, exceptions);

        int slot = 0;
        stubMethod.visitVarInsn(Opcodes.ALOAD, slot++); // load the receiver
        Type neededType = Type.getMethodType(desc);
        for (Type arg : neededType.getArgumentTypes()) {
          stubMethod.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
          slot += arg.getSize();
        }
        stubMethod.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            InterfaceDesugaring.getCompanionClassName(interfaceName),
            name,
            InterfaceDesugaring.companionDefaultMethodDescriptor(interfaceName, desc),
            /*itf*/ false);
        stubMethod.visitInsn(neededType.getReturnType().getOpcode(Opcodes.IRETURN));

        stubMethod.visitMaxs(0, 0); // rely on class writer to compute these
        stubMethod.visitEnd();
        return null;
      } else if (shouldStubAsBridgeDefaultMethod(access, name, desc)) {
        recordIfInstanceMethod(access, name, desc);
        depsCollector.assumeCompanionClass(
            internalName, InterfaceDesugaring.getCompanionClassName(interfaceName));
        // For bridges we just copy their bodies instead of going through the companion class.
        // Meanwhile, we also need to desugar the copied method bodies, so that any calls to
        // interface methods are correctly handled.
        return new InterfaceDesugaring.InterfaceInvocationRewriter(
            DefaultMethodClassFixer.this.visitMethod(access, name, desc, (String) null, exceptions),
            interfaceName,
            bootclasspath);
      } else {
        return null; // we don't care about the actual code in these methods
      }
    }
  }

  /**
   * Visitor for interfaces that recursively searches interfaces for default method declarations.
   */
  private class DefaultMethodFinder extends ClassVisitor {
    @SuppressWarnings("hiding")
    private ImmutableList<String> interfaces;

    private boolean found;

    public DefaultMethodFinder() {
      super(Opcodes.ASM5);
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      checkArgument(BitFlags.isSet(access, Opcodes.ACC_INTERFACE));
      checkState(this.interfaces == null);
      this.interfaces = ImmutableList.copyOf(interfaces);
    }

    public boolean foundDefaultMethods() {
      return found;
    }

    @Override
    public void visitEnd() {
      if (!found) {
        found = defaultMethodsDefined(this.interfaces);
      }
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String desc, String signature, String[] exceptions) {
      if (!found && shouldStubAsDefaultMethod(access, name, desc)) {
        // Found a default method we're not ignoring (instanceMethods at this point contains methods
        // the top-level visited class implements itself).
        found = true;
      }
      return null; // we don't care about the actual code in these methods
    }
  }

  private class InstanceMethodRecorder extends ClassVisitor {

    public InstanceMethodRecorder() {
      super(Opcodes.ASM5);
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      checkArgument(BitFlags.noneSet(access, Opcodes.ACC_INTERFACE));
      super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String desc, String signature, String[] exceptions) {
      recordIfInstanceMethod(access, name, desc);
      return null;
    }
  }

  /**
   * Detector to determine whether an interface needs to be initialized when it is loaded.
   *
   * <p>If the interface has a default method, and its <clinit> initializes any of its fields, then
   * this interface needs to be initialized.
   */
  private static class InterfaceInitializationNecessityDetector extends ClassVisitor {

    private final String internalName;
    private boolean hasFieldInitializedInClinit;
    private boolean hasDefaultMethods;

    public InterfaceInitializationNecessityDetector(String internalName) {
      super(Opcodes.ASM5);
      this.internalName = internalName;
    }

    public boolean needsToInitialize() {
      return hasDefaultMethods && hasFieldInitializedInClinit;
    }

    @Override
    public void visit(
        int version,
        int access,
        String name,
        String signature,
        String superName,
        String[] interfaces) {
      super.visit(version, access, name, signature, superName, interfaces);
      checkState(
          internalName.equals(name),
          "Inconsistent internal names: expected=%s, real=%s",
          internalName,
          name);
      checkArgument(
          BitFlags.isSet(access, Opcodes.ACC_INTERFACE),
          "This class visitor is only used for interfaces.");
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String desc, String signature, String[] exceptions) {
      if (!hasDefaultMethods) {
        hasDefaultMethods = isNonBridgeDefaultMethod(access);
      }
      if ("<clinit>".equals(name)) {
        return new MethodVisitor(Opcodes.ASM5) {
          @Override
          public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            if (opcode == Opcodes.PUTSTATIC && internalName.equals(owner)) {
              hasFieldInitializedInClinit = true;
            }
          }
        };
      }
      return null; // Do not care about the code.
    }
  }

  /** Comparator for interfaces that compares by whether interfaces extend one another. */
  enum InterfaceComparator implements Comparator<Class<?>> {
    INSTANCE;

    @Override
    public int compare(Class<?> o1, Class<?> o2) {
      checkArgument(o1.isInterface());
      checkArgument(o2.isInterface());
      if (o1 == o2) {
        return 0;
      }
      if (o1.isAssignableFrom(o2)) { // o1 is supertype of o2
        return 1; // we want o1 to come after o2
      }
      if (o2.isAssignableFrom(o1)) { // o2 is supertype of o1
        return -1; // we want o2 to come after o1
      }
      // o1 and o2 aren't comparable so arbitrarily impose lexicographical ordering
      return o1.getName().compareTo(o2.getName());
    }
  }
}
