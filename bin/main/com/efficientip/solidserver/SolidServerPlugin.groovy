/*
* Copyright 2022 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.efficientip.solidserver

import com.morpheusdata.core.Plugin

/**
 * The entrypoint of the EfficientIP SolidServer Plugin. This is where multiple providers can be registered (if necessary).
 * In the case of SolidServer a simple IPAM/DNS Provider is registered that enables functionality for those areas of automation.
 * 
 * @author David Estes 
 */
class SolidServerPlugin extends Plugin {

	@Override
	String getCode() {
		return 'morpheus-efficientip-plugin'
	}

	@Override
	void initialize() {
		 SolidServerProvider solidServerProvider = new SolidServerProvider(this, morpheus)
		 this.pluginProviders.put("solidserver", solidServerProvider)
		 this.setName("EfficientIP SolidServer")
	}

	/**
	 * Called when a plugin is being removed from the plugin manager (aka Uninstalled)
	 */
	@Override
	void onDestroy() {
		//nothing to do for now
	}
}
