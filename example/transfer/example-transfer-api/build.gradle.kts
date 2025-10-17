@file:OptIn(KspExperimental::class)

import com.google.devtools.ksp.KspExperimental

plugins {
    alias(libs.plugins.ksp)
}
dependencies {
    api(platform(project(":wow-dependencies")))
    api("io.swagger.core.v3:swagger-core-jakarta")
    implementation(project(":wow-api"))
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    api("jakarta.validation:jakarta.validation-api")
    ksp(project(":wow-compiler"))
}

ksp {
//    TODO
//    fix: [ksp] java.lang.ClassCastException: class ksp.com.intellij.psi.impl.source.PsiRecordComponentImpl
//    cannot be cast to class ksp.com.intellij.psi.PsiJvmModifiersOwner (ksp.com.intellij.psi.impl.source.PsiRecordComponentImpl
//    and ksp.com.intellij.psi.PsiJvmModifiersOwner are in unnamed module of loader java.net.URLClassLoader @6e1a421c)
    useKsp2.set(true)
}