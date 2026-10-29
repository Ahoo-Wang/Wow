description = "Wow view store: the view, view preferences and dataset wording aggregates (roadmap 7.2a)"

// The 9.x view store's domain (view-store/wow-view-store-domain), ported with the migration Skill (roadmap M7, H5).
// The plugin `me.ahoo.wow` compiles the aggregates and the saga into kernel models, their binding index and the
// module's Manifest fragment, and offers its contract to :view-store-api, as the eShop's domains do.
plugins {
    id("me.ahoo.wow")
}

wow {
    service = "view-store-service"
}

dependencies {
    api(project(":view-store-api"))
    testImplementation("me.ahoo.wow:wow-test")
    // The Manifest fragment the compiler writes, which the tests read (the kernel does not bring :manifest, D231).
    testImplementation("me.ahoo.wow:wow-manifest")
}
