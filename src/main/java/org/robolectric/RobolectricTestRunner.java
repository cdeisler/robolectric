package org.robolectric;

import android.app.Application;
import android.os.Build;
import org.apache.maven.artifact.ant.DependenciesTask;
import org.apache.maven.model.Dependency;
import org.apache.tools.ant.Project;
import org.jetbrains.annotations.TestOnly;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.DisableStrictI18n;
import org.robolectric.annotation.EnableStrictI18n;
import org.robolectric.annotation.WithConstantInt;
import org.robolectric.annotation.WithConstantString;
import org.robolectric.bytecode.AndroidTranslator;
import org.robolectric.bytecode.AsmInstrumentingClassLoader;
import org.robolectric.bytecode.ClassCache;
import org.robolectric.bytecode.ClassHandler;
import org.robolectric.bytecode.JavassistInstrumentingClassLoader;
import org.robolectric.bytecode.RobolectricInternals;
import org.robolectric.bytecode.Setup;
import org.robolectric.bytecode.ShadowMap;
import org.robolectric.bytecode.ShadowWrangler;
import org.robolectric.bytecode.ZipClassCache;
import org.robolectric.internal.ParallelUniverse;
import org.robolectric.internal.ParallelUniverseInterface;
import org.robolectric.internal.TestLifecycle;
import org.robolectric.res.OverlayResourceLoader;
import org.robolectric.res.PackageResourceLoader;
import org.robolectric.res.ResourceLoader;
import org.robolectric.res.ResourcePath;
import org.robolectric.res.RoutingResourceLoader;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.DatabaseConfig;
import org.robolectric.util.DatabaseConfig.DatabaseMap;
import org.robolectric.util.DatabaseConfig.UsingDatabaseMap;
import org.robolectric.util.SQLiteMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import static org.fest.reflect.core.Reflection.staticField;

/**
 * Installs a {@link org.robolectric.bytecode.InstrumentingClassLoader} and
 * {@link org.robolectric.res.ResourceLoader} in order to
 * provide a simulation of the Android runtime environment.
 */
public class RobolectricTestRunner extends BlockJUnit4ClassRunner {
    private static final Map<Class<? extends RobolectricTestRunner>, RobolectricContext> contextsByTestRunner = new WeakHashMap<Class<? extends RobolectricTestRunner>, RobolectricContext>();
    private static final Map<AndroidManifest, ResourceLoader> resourceLoadersByAppManifest = new HashMap<AndroidManifest, ResourceLoader>();
    private static final Map<ResourcePath, ResourceLoader> systemResourceLoaders = new HashMap<ResourcePath, ResourceLoader>();

    private static ShadowMap mainShadowMap;

    private RobolectricContext robolectricContext;
    private DatabaseMap databaseMap;
    private TestLifecycle<Application> testLifecycle;

    /**
     * Creates a runner to run {@code testClass}. Looks in your working directory for your AndroidManifest.xml file
     * and res directory.
     *
     * @param testClass the test class to be run
     * @throws InitializationError if junit says so
     */
    public RobolectricTestRunner(final Class<?> testClass) throws InitializationError {
        super(testClass);

        RobolectricContext robolectricContext;
        synchronized (contextsByTestRunner) {
            Class<? extends RobolectricTestRunner> testRunnerClass = getClass();
            robolectricContext = contextsByTestRunner.get(testRunnerClass);
            if (robolectricContext == null) {
                try {
                    robolectricContext = createRobolectricContext();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                contextsByTestRunner.put(testRunnerClass, robolectricContext);
            }
        }
        this.robolectricContext = robolectricContext;

        try {
            testLifecycle = (TestLifecycle) robolectricContext.getRobolectricClassLoader().loadClass(getTestLifecycleClass().getName()).newInstance();
            testLifecycle.init(robolectricContext);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        databaseMap = setupDatabaseMap(testClass, new SQLiteMap());
        Thread.currentThread().setContextClassLoader(robolectricContext.getRobolectricClassLoader());
    }

    public RobolectricContext createRobolectricContext() {
        Setup setup = createSetup();
        ClassHandler classHandler = createClassHandler(setup);
        AndroidManifest appManifest = createAppManifest();
        ClassLoader robolectricClassLoader = createRobolectricClassLoader(setup);
        injectClassHandler(robolectricClassLoader, classHandler);

        return new RobolectricContext(appManifest, classHandler, robolectricClassLoader);
    }

    private ClassHandler createClassHandler(Setup setup) {
        return new ShadowWrangler(setup);
    }

    protected AndroidManifest createAppManifest() {
        return new AndroidManifest(new File("."));
    }

    public Setup createSetup() {
        return new Setup();
    }

    protected Class<? extends TestLifecycle> getTestLifecycleClass() {
        return DefaultTestLifecycle.class;
    }

    protected ClassLoader createRobolectricClassLoader(Setup setup) {
        URL[] urls = artifactUrls(realAndroidDependency("android-base"),
                realAndroidDependency("android-kxml2"),
                realAndroidDependency("android-luni"),
                createDependency("org.json", "json", "20080701", "jar", null)
        );
        ClassLoader robolectricClassLoader;
        if (useAsm()) {
            robolectricClassLoader = new AsmInstrumentingClassLoader(setup, urls);
        } else {
            ClassCache classCache = createClassCache();
            AndroidTranslator androidTranslator = createAndroidTranslator(setup, classCache);
            ClassLoader realSdkClassLoader = JavassistInstrumentingClassLoader.makeClassloader(this.getClass().getClassLoader(), urls);
            robolectricClassLoader = new JavassistInstrumentingClassLoader(realSdkClassLoader, classCache, androidTranslator, setup);
        }
        return robolectricClassLoader;
    }

    public ClassCache createClassCache() {
        final String classCachePath = System.getProperty("cached.robolectric.classes.path");
        final File classCacheDirectory;
        if (null == classCachePath || "".equals(classCachePath.trim())) {
            classCacheDirectory = new File("./tmp");
        } else {
            classCacheDirectory = new File(classCachePath);
        }

        return new ZipClassCache(new File(classCacheDirectory, "cached-robolectric-classes.jar").getAbsolutePath(), AndroidTranslator.CACHE_VERSION);
    }

    public AndroidTranslator createAndroidTranslator(Setup setup, ClassCache classCache) {
        return new AndroidTranslator(classCache, setup);
    }

    public boolean useAsm() {
        return true;
    }

    private void injectClassHandler(ClassLoader robolectricClassLoader, ClassHandler classHandler) {
        try {
            String className = RobolectricInternals.class.getName();
            Class<?> robolectricInternalsClass = robolectricClassLoader.loadClass(className);
            Field field = robolectricInternalsClass.getDeclaredField("classHandler");
            field.setAccessible(true);
            field.set(null, classHandler);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private URL[] artifactUrls(Dependency... dependencies) {
        DependenciesTask dependenciesTask = new DependenciesTask();
        configureMaven(dependenciesTask);
        Project project = new Project();
        dependenciesTask.setProject(project);
        for (Dependency dependency : dependencies) {
            dependenciesTask.addDependency(dependency);
        }
        dependenciesTask.execute();

        @SuppressWarnings("unchecked")
        Hashtable<String, String> artifacts = project.getProperties();
        URL[] urls = new URL[artifacts.size()];
        int i = 0;
        for (String path : artifacts.values()) {
            try {
                urls[i++] = new URL("file://" + path);
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }

        return urls;
    }

    @SuppressWarnings("UnusedParameters")
    protected void configureMaven(DependenciesTask dependenciesTask) {
        // maybe you want to override this method and some settings?
    }

    private Dependency realAndroidDependency(String artifactId) {
        // right now we only have real jars for Ice Cream Sandwich aka 4.1 aka API 16
        return createDependency("org.robolectric", artifactId, "4.1.2_r1_rc", "jar", "real");
    }

    private Dependency createDependency(String groupId, String artifactId, String version, String type, String classifier) {
        Dependency dependency = new Dependency();
        dependency.setGroupId(groupId);
        dependency.setArtifactId(artifactId);
        dependency.setVersion(version);
        dependency.setType(type);
        dependency.setClassifier(classifier);
        return dependency;
    }

    public RobolectricContext getRobolectricContext() {
        return robolectricContext;
    }

    @Override
    protected Statement classBlock(RunNotifier notifier) {
        Class bootstrappedTestClass = robolectricContext.bootstrappedClass(getTestClass().getJavaClass());
        HelperTestRunner helperTestRunner;
        try {
            helperTestRunner = new HelperTestRunner(bootstrappedTestClass);
        } catch (InitializationError initializationError) {
            throw new RuntimeException(initializationError);
        }

        final Statement statement = helperTestRunner.classBlock(notifier);
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    statement.evaluate();
                } finally {
                    afterClass();
                }
            }
        };
    }

    @Override protected Statement methodBlock(final FrameworkMethod method) {
        throw new UnsupportedOperationException("shouldn't be called");
    }

    protected void configureShadows(Config config) {
        ShadowMap shadowMap = createShadowMap();

        if (config != null) {
            Class<?>[] shadows = config.shadows();
            if (shadows.length > 0) {
                shadowMap = new ShadowMap.Builder(shadowMap)
                        .addShadowClasses(shadows)
                        .build();
            }
        }

        ((ShadowWrangler) robolectricContext.getClassHandler()).setShadowMap(shadowMap);
    }

    /*
     * Called before each test method is run. Sets up the simulation of the Android runtime environment.
     */
    protected void internalBeforeTest(final Method method, DatabaseConfig.DatabaseMap databaseMap, Config config) {
        ParallelUniverseInterface parallelUniverseInterface = getHooksInterface();
        parallelUniverseInterface.resetStaticState();
        parallelUniverseInterface.setDatabaseMap(databaseMap); //Set static DatabaseMap in DBConfig

        boolean strictI18n = RobolectricTestRunner.determineI18nStrictState(method);
        ClassHandler classHandler = robolectricContext.getClassHandler();
        classHandler.setStrictI18n(strictI18n);

        int sdkVersion = pickReportedSdkVersion(config);
        Class<?> versionClass = robolectricContext.bootstrappedClass(Build.VERSION.class);
        staticField("SDK_INT").ofType(int.class).in(versionClass).set(sdkVersion);

        ResourcePath systemResourcePath = robolectricContext.getSystemResourcePath();
        ResourceLoader systemResourceLoader = getSystemResourceLoader(systemResourcePath);
        setupApplicationState(method, parallelUniverseInterface, strictI18n, systemResourceLoader);
        testLifecycle.beforeTest(method);
    }

    protected void setupApplicationState(Method method, ParallelUniverseInterface parallelUniverseInterface, boolean strictI18n, ResourceLoader systemResourceLoader) {
        parallelUniverseInterface.setupApplicationState(method, testLifecycle, robolectricContext, strictI18n, systemResourceLoader);
    }

    private int getTargetSdkVersion() {
        AndroidManifest appManifest = robolectricContext.getAppManifest();
        return getTargetVersionWhenAppManifestMightBeNullWhaaa(appManifest);
    }

    public static int getTargetVersionWhenAppManifestMightBeNullWhaaa(AndroidManifest appManifest) {
        return appManifest == null // app manifest would be null for libraries
                ? Build.VERSION_CODES.ICE_CREAM_SANDWICH // todo: how should we be picking this?
                : appManifest.getTargetSdkVersion();
    }

    protected int pickReportedSdkVersion(Config config) {
        if (config != null && config.reportSdk() != -1) {
            return config.reportSdk();
        } else {
            return getTargetSdkVersion();
        }
    }

    private ParallelUniverseInterface getHooksInterface() {
        try {
            @SuppressWarnings("unchecked")
            Class<ParallelUniverseInterface> aClass = (Class<ParallelUniverseInterface>) robolectricContext.getRobolectricClassLoader().loadClass(ParallelUniverse.class.getName());
            return aClass.newInstance();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public void internalAfterTest(final Method method) {
        testLifecycle.afterTest(method);
    }

    private void afterClass() {
        testLifecycle = null;
        robolectricContext = null;
        databaseMap = null;
    }

    @TestOnly
    boolean allStateIsCleared() {
        return testLifecycle == null && robolectricContext == null && databaseMap == null;
    }

    @Override
    public Object createTest() throws Exception {
        throw new UnsupportedOperationException("this should always be invoked on the HelperTestRunner!");
    }

    public static String determineResourceQualifiers(Method method) {
        String qualifiers = "";
        Config config = method.getAnnotation(Config.class);
        if (config != null) {
            qualifiers = config.qualifiers();
        }
        return qualifiers;
    }

    /**
     * Sets Robolectric config to determine if Robolectric should blacklist API calls that are not
     * I18N/L10N-safe.
     * <p/>
     * I18n-strict mode affects suitably annotated shadow methods. Robolectric will throw exceptions
     * if these methods are invoked by application code. Additionally, Robolectric's ResourceLoader
     * will throw exceptions if layout resources use bare string literals instead of string resource IDs.
     * <p/>
     * To enable or disable i18n-strict mode for specific test cases, annotate them with
     * {@link org.robolectric.annotation.EnableStrictI18n} or
     * {@link org.robolectric.annotation.DisableStrictI18n}.
     * <p/>
     * <p/>
     * By default, I18n-strict mode is disabled.
     *
     * @param method
     */
    public static boolean determineI18nStrictState(Method method) {
        // Global
        boolean strictI18n = globalI18nStrictEnabled();

        // Test case class
        Class<?> testClass = method.getDeclaringClass();
        if (testClass.getAnnotation(EnableStrictI18n.class) != null) {
            strictI18n = true;
        } else if (testClass.getAnnotation(DisableStrictI18n.class) != null) {
            strictI18n = false;
        }

        // Test case method
        if (method.getAnnotation(EnableStrictI18n.class) != null) {
            strictI18n = true;
        } else if (method.getAnnotation(DisableStrictI18n.class) != null) {
            strictI18n = false;
        }

        return strictI18n;
    }

    /**
     * Default implementation of global switch for i18n-strict mode.
     * To enable i18n-strict mode globally, set the system property
     * "robolectric.strictI18n" to true. This can be done via java
     * system properties in either Ant or Maven.
     * <p/>
     * Subclasses can override this method and establish their own policy
     * for enabling i18n-strict mode.
     *
     * @return
     */
    protected static boolean globalI18nStrictEnabled() {
        return Boolean.valueOf(System.getProperty("robolectric.strictI18n"));
    }

    /**
     * Find all the class and method annotations and pass them to
     * addConstantFromAnnotation() for evaluation.
     * <p/>
     * TODO: Add compound annotations to support defining more than one int and string at a time
     * TODO: See http://stackoverflow.com/questions/1554112/multiple-annotations-of-the-same-type-on-one-element
     *
     * @param method
     * @return
     */
    private Map<Field, Object> getWithConstantAnnotations(Method method) {
        Map<Field, Object> constants = new HashMap<Field, Object>();

        for (Annotation anno : method.getDeclaringClass().getAnnotations()) {
            addConstantFromAnnotation(constants, anno);
        }

        for (Annotation anno : method.getAnnotations()) {
            addConstantFromAnnotation(constants, anno);
        }

        return constants;
    }


    /**
     * If the annotation is a constant redefinition, add it to the provided hash
     *
     * @param constants
     * @param anno
     */
    private void addConstantFromAnnotation(Map<Field, Object> constants, Annotation anno) {
        try {
            String name = anno.annotationType().getName();
            Object newValue = null;

            if (name.equals(WithConstantString.class.getName())) {
                newValue = anno.annotationType().getMethod("newValue").invoke(anno);
            } else if (name.equals(WithConstantInt.class.getName())) {
                newValue = anno.annotationType().getMethod("newValue").invoke(anno);
            } else {
                return;
            }

            @SuppressWarnings("rawtypes")
            Class classWithField = (Class) anno.annotationType().getMethod("classWithField").invoke(anno);
            String fieldName = (String) anno.annotationType().getMethod("fieldName").invoke(anno);
            Field field = classWithField.getDeclaredField(fieldName);
            constants.put(field, newValue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Defines static finals from the provided hash and stores the old values back
     * into the hash.
     * <p/>
     * Call it twice with the same hash, and it puts everything back the way it was originally.
     *
     * @param constants
     */
    private void setupConstants(Map<Field, Object> constants) {
        for (Field field : constants.keySet()) {
            Object newValue = constants.get(field);
            Object oldValue = Robolectric.Reflection.setFinalStaticField(field, newValue);
            constants.put(field, oldValue);
        }
    }

    public static ResourceLoader getSystemResourceLoader(ResourcePath systemResourcePath) {
        ResourceLoader systemResourceLoader = systemResourceLoaders.get(systemResourcePath);
        if (systemResourceLoader == null) {
            systemResourceLoader = createResourceLoader(systemResourcePath);
            systemResourceLoaders.put(systemResourcePath, systemResourceLoader);
        }
        return systemResourceLoader;
    }

    public static ResourceLoader getAppResourceLoader(ResourceLoader systemResourceLoader, final AndroidManifest appManifest) {
        ResourceLoader resourceLoader = resourceLoadersByAppManifest.get(appManifest);
        if (resourceLoader == null) {
            resourceLoader = createAppResourceLoader(systemResourceLoader, appManifest);
            resourceLoadersByAppManifest.put(appManifest, resourceLoader);
        }
        return resourceLoader;
    }

    // this method must live on a InstrumentingClassLoader-loaded class, so it can't be on RobolectricContext
    protected static ResourceLoader createAppResourceLoader(ResourceLoader systemResourceLoader, AndroidManifest appManifest) {
        List<PackageResourceLoader> appAndLibraryResourceLoaders = new ArrayList<PackageResourceLoader>();
        for (ResourcePath resourcePath : appManifest.getIncludedResourcePaths()) {
            appAndLibraryResourceLoaders.add(new PackageResourceLoader(resourcePath));
        }
        OverlayResourceLoader overlayResourceLoader = new OverlayResourceLoader(appManifest.getPackageName(), appAndLibraryResourceLoaders);

        Map<String, ResourceLoader> resourceLoaders = new HashMap<String, ResourceLoader>();
        resourceLoaders.put("android", systemResourceLoader);
        resourceLoaders.put(appManifest.getPackageName(), overlayResourceLoader);
        return new RoutingResourceLoader(resourceLoaders);
    }

    public static PackageResourceLoader createResourceLoader(ResourcePath systemResourcePath) {
        return new PackageResourceLoader(systemResourcePath);
    }

    /*
     * Specifies what database to use for testing (ex: H2 or Sqlite),
     * this will load H2 by default, the SQLite TestRunner version will override this.
     */
    protected DatabaseMap setupDatabaseMap(Class<?> testClass, DatabaseMap map) {
        DatabaseMap dbMap = map;

        if (testClass.isAnnotationPresent(UsingDatabaseMap.class)) {
            UsingDatabaseMap usingMap = testClass.getAnnotation(UsingDatabaseMap.class);
            if (usingMap.value() != null) {
                dbMap = Robolectric.newInstanceOf(usingMap.value());
            } else {
                if (dbMap == null)
                    throw new RuntimeException("UsingDatabaseMap annotation value must provide a class implementing DatabaseMap");
            }
        }
        return dbMap;
    }

    protected ShadowMap createShadowMap() {
        synchronized (RobolectricTestRunner.class) {
            if (mainShadowMap != null) return mainShadowMap;

            mainShadowMap = new ShadowMap.Builder()
                    .addShadowClasses(RobolectricBase.DEFAULT_SHADOW_CLASSES)
                    .build();
            return mainShadowMap;
        }
    }


    private void setupLogging() {
        String logging = System.getProperty("robolectric.logging");
        if (logging != null && ShadowLog.stream == null) {
            PrintStream stream = null;
            if ("stdout".equalsIgnoreCase(logging)) {
                stream = System.out;
            } else if ("stderr".equalsIgnoreCase(logging)) {
                stream = System.err;
            } else {
                try {
                    final PrintStream file = new PrintStream(new FileOutputStream(logging));
                    stream = file;
                    Runtime.getRuntime().addShutdownHook(new Thread() {
                        @Override public void run() {
                            try {
                                file.close();
                            } catch (Exception ignored) {
                            }
                        }
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            ShadowLog.stream = stream;
        }
    }

    public class HelperTestRunner extends BlockJUnit4ClassRunner {
        public HelperTestRunner(Class<?> testClass) throws InitializationError {
            super(testClass);
        }

        @Override protected Object createTest() throws Exception {
            Object test = super.createTest();
            testLifecycle.prepareTest(test);
            return test;
        }

        @Override public Statement classBlock(RunNotifier notifier) {
            return super.classBlock(notifier);
        }

        @Override public Statement methodBlock(FrameworkMethod method) {
            setupLogging();

            Config config = method.getMethod().getAnnotation(Config.class);
            configureShadows(config);


            final Method bootstrappedMethod = method.getMethod();
            try {
                internalBeforeTest(bootstrappedMethod, databaseMap, config);
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }

            final Statement statement = super.methodBlock(new FrameworkMethod(bootstrappedMethod));
            return new Statement() {
                @Override public void evaluate() throws Throwable {
                    Map<Field, Object> withConstantAnnos = getWithConstantAnnotations(bootstrappedMethod);

                    // todo: this try/finally probably isn't right -- should mimic RunAfters? [xw]
                    try {
                        if (withConstantAnnos.isEmpty()) {
                            statement.evaluate();
                        } else {
                            synchronized (this) {
                                setupConstants(withConstantAnnos);
                                statement.evaluate();
                                setupConstants(withConstantAnnos);
                            }
                        }
                    } finally {
                        internalAfterTest(bootstrappedMethod);
                    }
                }
            };
        }
    }
}
