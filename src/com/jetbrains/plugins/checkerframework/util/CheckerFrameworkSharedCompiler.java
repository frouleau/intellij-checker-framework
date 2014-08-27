package com.jetbrains.plugins.checkerframework.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Key;
import com.intellij.psi.*;
import com.jetbrains.plugins.checkerframework.tools.*;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Check;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.comp.Todo;
import com.sun.tools.javac.jvm.ClassReader;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import org.apache.log4j.Level;
import org.checkerframework.framework.source.AggregateChecker;
import org.checkerframework.framework.source.SourceChecker;
import org.checkerframework.stubparser.JavaParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.sun.tools.javac.code.Symbol.ClassSymbol;

@SuppressWarnings("UseOfSystemOutOrSystemErr")
public class CheckerFrameworkSharedCompiler extends PsiTreeChangeAdapter {

    @SuppressWarnings("UnusedDeclaration")
    private static final Logger                  LOG           = Logger.getInstance(CheckerFrameworkSharedCompiler.class);
    private static final JavacTool               JAVA_COMPILER = JavacTool.create();
    private static final StandardJavaFileManager FILE_MANAGER  = JAVA_COMPILER.getStandardFileManager(null, null, null);
    private static final Key<Boolean>            KEY           = Key.create("usedInSymtab");

    static {
        JavaParser.setCacheParser(false);
        LOG.setLevel(Level.DEBUG);
        LOG.debug("lol");
    }

    private final FilteringDiagnosticCollector myDiagnosticCollector = new FilteringDiagnosticCollector();
    private final Set<PsiJavaFile>             myChangedFiles        = new HashSet<PsiJavaFile>();
    private final JavaFileManager       fileManager;
    private final Context               mySharedContext;
    private final AggregateCheckerEx    processor;
    private final ProcessingEnvironment environment;
    private final JavaCompiler          compiler;
    private final Check                 check;
    private final Todo                  todo;
    private final Names                 names;
    private final Symtab                symtab;
    private final ClassReader           myClassReader;
    private final AtomicReference<Boolean> symtabRunning     = new AtomicReference<Boolean>(false);
    private final AtomicReference<Boolean> processingRunning = new AtomicReference<Boolean>(false);

    public CheckerFrameworkSharedCompiler(final @NotNull Project project,
                                          final @NotNull Collection<String> compileOptions,
                                          final @NotNull Collection<Class<? extends SourceChecker>> classes) {
        System.out.println("Compiler instance creating start");
        long startTime = System.currentTimeMillis();

        fileManager = new PsiJavaFileManager(FILE_MANAGER, project);
        mySharedContext = new ThreadContext();
        { // init context with own file manager
            ThreadedTrees.preRegister(mySharedContext);
            mySharedContext.put(DiagnosticListener.class, myDiagnosticCollector);
            mySharedContext.put(JavaFileManager.class, fileManager);
            JavacTool.processOptions(mySharedContext, fileManager, compileOptions);
        }
        todo = Todo.instance(mySharedContext);
        compiler = JavaCompiler.instance(mySharedContext);
        names = Names.instance(mySharedContext);
        symtab = Symtab.instance(mySharedContext);
        check = Check.instance(mySharedContext);
        environment = JavacProcessingEnvironment.instance(mySharedContext);
        myClassReader = ClassReader.instance(mySharedContext);

        // init processor
        processor = new AggregateCheckerEx() {
            @Override
            protected Collection<Class<? extends SourceChecker>> getSupportedCheckers() {
                return classes;
            }
        };
        processor.setProcessingEnvironment(environment);
        processor.initChecker();
        System.out.println("Compiler instance created, " + (System.currentTimeMillis() - startTime) + "ms elapsed");
        PsiManager.getInstance(project).addPsiTreeChangeListener(this);
    }

    @SuppressWarnings("UnusedDeclaration")
    @Nullable
    public List<Diagnostic<? extends JavaFileObject>> getMessages(@NotNull final PsiJavaFile psiJavaFile, boolean isOnTheFly) {
        if (processingRunning.compareAndSet(true, true)) {
            return null;
        }
        {   // remove & reenter changed files
            for (final PsiJavaFile javaFile : myChangedFiles) {
                clean(javaFile);
                final PsiJavaFileObject javaFileObject = new PsiJavaFileObject(javaFile);
                myClassReader.enterClass(
                    names.fromString(fileManager.inferBinaryName(null, javaFileObject)),
                    javaFileObject
                );
            }
            myChangedFiles.clear();
        }
        final Callable<List<ClassSymbol>> classSymbolCompleter = new Completer(psiJavaFile);
        if (psiJavaFile.getUserData(KEY) != null || !isOnTheFly) {
            final List<ClassSymbol> classSymbols;
            try {
                classSymbols = classSymbolCompleter.call();
            } catch (Exception e) {
                LOG.error(e);
                return null;
            }
            final Future future = ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
                @Override
                public void run() {
                    for (ClassSymbol classSymbol : classSymbols) {
                        long start = System.currentTimeMillis();
                        try {
                            System.out.println("Processing " + classSymbol + " start");
                            synchronized (processingRunning) {
                                processor.typeProcess(classSymbol, Trees.instance(environment).getPath(classSymbol));
                            }
                        } catch (EnoughException e) {
                            LOG.debug(e);
                        } finally {
                            System.out.println("Processing " + classSymbol + " " + (System.currentTimeMillis() - start) + "ms elapsed");
                        }
                    }
                }
            });
            try {
                if (isOnTheFly) {
                    future.get(1000, TimeUnit.MILLISECONDS);
                } else {
                    future.get();
                }
            } catch (InterruptedException e) {
                myDiagnosticCollector.getAndClear();
                return null;
            } catch (ExecutionException e) {
                myDiagnosticCollector.getAndClear();
                return null;
            } catch (TimeoutException e) {
                future.cancel(true);
                myDiagnosticCollector.getAndClear();
                return null;
            }
            return myDiagnosticCollector.getAndClear();
        } else {
            psiJavaFile.putUserData(KEY, true);
            ApplicationManager.getApplication().executeOnPooledThread(classSymbolCompleter);
            return null;
        }
    }

    public Name getFlatName(PsiClass psiClass) {
        return names.fromString(
            psiClass.getContainingClass() == null
                ? psiClass.getQualifiedName()
                : psiClass.getContainingClass().getQualifiedName() + "$" + psiClass.getName()
        );
    }

    public boolean clean(PsiClass psiClass) {
        final Name flatname = getFlatName(psiClass);
        final ClassSymbol old = symtab.classes.remove(flatname);
        check.compiled.remove(flatname);
        for (PsiClass inner : psiClass.getInnerClasses()) {
            if (psiClass.getQualifiedName() != null) {
                clean(inner);
            }
        }
        return old != null;
    }

    public void clean(PsiJavaFile javaFile) {
        for (PsiClass psiClass : javaFile.getClasses()) {
            clean(psiClass);
        }
    }

    private void cleanLater(PsiFile file) {
        if (file instanceof PsiJavaFile) {
            myChangedFiles.add((PsiJavaFile) file);
        }
    }

    private void cleanLater(PsiTreeChangeEvent event) {
        cleanLater(event.getFile());
    }

    @Override
    public void childAdded(@NotNull PsiTreeChangeEvent event) {
        cleanLater(event);
    }

    @Override
    public void childRemoved(@NotNull PsiTreeChangeEvent event) {
        cleanLater(event);
    }

    @Override
    public void childReplaced(@NotNull PsiTreeChangeEvent event) {
        cleanLater(event);
    }

    @Override
    public void childMoved(@NotNull PsiTreeChangeEvent event) {
        cleanLater(event);
    }

    @Override
    public void childrenChanged(@NotNull PsiTreeChangeEvent event) {
        cleanLater(event);
    }

    private class Completer implements Callable<List<ClassSymbol>> {

        private final PsiJavaFile myPsiJavaFile;

        private Completer(PsiJavaFile psiJavaFile) {
            this.myPsiJavaFile = psiJavaFile;
        }

        @Override
        public List<ClassSymbol> call() throws Exception {
            long start = System.currentTimeMillis();

            try {
                processingRunning.set(true);
                final ClassSymbol classSymbol = ApplicationManager.getApplication().runReadAction(new Computable<ClassSymbol>() {
                    @Override
                    public ClassSymbol compute() {
                        clean(myPsiJavaFile);
                        final PsiJavaFileObject javaFileObject = new PsiJavaFileObject(myPsiJavaFile);
                        return ClassReader.instance(mySharedContext).enterClass(
                            names.fromString(fileManager.inferBinaryName(null, javaFileObject)),
                            javaFileObject
                        );
                    }
                });
                classSymbol.complete();
                while (!todo.isEmpty()) {
                    final Env<AttrContext> current = todo.remove();
                    synchronized (todo) {
                        compiler.flow(compiler.attribute(current));
                    }
                }

                return ApplicationManager.getApplication().runReadAction(new Computable<List<ClassSymbol>>() {
                    @Override
                    public List<ClassSymbol> compute() {
                        final List<ClassSymbol> result = new ArrayList<ClassSymbol>();
                        for (final PsiClass psiClass : myPsiJavaFile.getClasses()) {
                            final Name name = getFlatName(psiClass);
                            result.add(symtab.classes.get(name));
                        }
                        return result;
                    }
                });
            } finally {
                System.out.println(System.currentTimeMillis() - start + "ms elapsed");
                processingRunning.set(false);
            }
        }
    }

    private abstract static class AggregateCheckerEx extends AggregateChecker {

        @Override
        public void setProcessingEnvironment(ProcessingEnvironment env) {
            super.setProcessingEnvironment(env);
        }
    }
}
