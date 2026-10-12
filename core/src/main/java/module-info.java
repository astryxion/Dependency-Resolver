module com.dependencyresolver {
    requires java.desktop;
    requires java.instrument;
    requires java.logging;
    requires java.net.http;
    requires cpw.mods.modlauncher;
    requires fmlloader;
    requires net.minecraftforge.forgespi;

    exports com.dependencyresolver.bootstrap;
    exports com.dependencyresolver.dependency;
    exports com.dependencyresolver.download;
    exports com.dependencyresolver.early;
    exports com.dependencyresolver.instance;
    exports com.dependencyresolver.json;
    exports com.dependencyresolver.log;
    exports com.dependencyresolver.platform;
    exports com.dependencyresolver.ui;

    provides cpw.mods.modlauncher.api.ITransformationService
            with com.dependencyresolver.early.EarlyDependencyService;
    provides net.minecraftforge.forgespi.locating.IModLocator
            with com.dependencyresolver.early.SelfModLocator;
}
