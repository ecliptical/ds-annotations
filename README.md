# Declarative Services Annotations Support

OSGi Declarative Services offer a powerful mechanism for developing complex, service-oriented applications. However, Eclipse PDE tooling to support this functionality has been lacking.

This project delivers an Eclipse plugin with the ability to automatically generate and update DS Component Definition files from appropriately annotated source code. It does this without adding any custom builders to your project. In addition to generating/updating the descriptor files it also maintains the bundle's MANIFEST.MF and build.properties file.

## Installation

A public p2 repository with this feature is available at http://download.eclipticalsoftware.com/updates/.

## Configuration

The functionality is enabled by default. To disable and/or change the default descriptor folder, go to _Preferences -> DS Annotations_.

Individual projects may override these settings in the project's _Properties -> DS Annotations_ property page.

## Usage

Simply import package org.osgi.service.component.annotations into your PDE Plug-in project and annotate your component implementation classes with @Component and related annotations. The DS Annotations Support plug-in will do the rest.

Note that the descriptor files generated from source annotations are overwritten on every source change; however, manually created decriptor files (i.e., those that are not generated from annotated classes) are left unchanged. Thus it is possible to combine automated generation with manually created and maintained descriptors.

## License

This software is made available under the terms of the Eclipse Public License v1.0 which accompanies this distribution, and is available at http://www.eclipse.org/legal/epl-v10.html.

----------------------------------------------------------------
Copyright (c) 2013 Ecliptical Software Inc. All rights reserved.
