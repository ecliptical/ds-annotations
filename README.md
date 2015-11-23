# Declarative Services Annotations Support

OSGi Declarative Services offer a powerful mechanism for developing complex, service-oriented applications. However, Eclipse PDE tooling to support this functionality has been lacking.

This project delivers an Eclipse plug-in with the ability to automatically generate and update DS Component Definition files from appropriately annotated source code. It does this without adding any custom builders to your project. In addition to generating/updating the descriptor files it also maintains the bundle's MANIFEST.MF and build.properties file.

## Installation

A public p2 repository with this feature is available at http://download.eclipticalsoftware.com/updates/.

## Configuration

The functionality is enabled by default. To disable and/or change the default descriptor folder, go to _Preferences -> DS Annotations_.

Individual projects may override these settings in the project's _Properties -> DS Annotations_ property page.

## Usage

In a PDE Plug-in project, simply annotate your component implementation classes with @Component and related annotations; the DS Annotations Support plug-in will do the rest<a href="#classpath">*</a>.

Note that the descriptor files generated from source annotations are overwritten on every source change; however, manually created descriptor files (i.e., those that are not generated from annotated classes) are left unchanged. Thus it is possible to combine automated generation with manually created and maintained descriptors.

-----
<a name="classpath" id="classpath">*</a> By default the plug-in makes DS Annotation types available to all PDE Plug-in projects in the workspace. However, you must make sure they are also added to your project's "permanent" build path used by external builders outside of the workbench. There are several ways to accomplish that. E.g.,

*A. Additional bundle*: Add bundle _ca.ecliptical.pde.ds.lib_ under _Automated Management of Dependencies_ in your Plug-in Manifest Editor's _Dependencies_ tab.

*B. Extra library*: Add the following line to your Plug-in project's build.properties file:

	extra.. = platform:/plugin/ca.ecliptical.pde.ds.lib/annotations.jar

> Or: 

	jars.extra.classpath = platform:/plugin/ca.ecliptical.pde.ds.lib/annotations.jar

See [PDE documentation](http://help.eclipse.org/luna/index.jsp?topic=%2Forg.eclipse.pde.doc.user%2Freference%2Fpde_feature_generating_build.htm "Feature and Plug-in Build Configuration Properties") for more details.

*C. Import package*: In your Plug-in Manifest Editor's _Dependencies_ tab import package _org.osgi.service.component.annotations_ and mark it as optional. This is the least preferred option as it unnecessarily modifies your bundle's runtime classpath (in META-INF/MANIFEST.MF).

## License

This software is made available under the terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at http://www.eclipse.org/legal/epl-v10.html.

----------------------------------------------------------------------
Copyright (c) 2013, 2015 Ecliptical Software Inc. All rights reserved.
