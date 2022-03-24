package com.efficientip.solidserver

import com.morpheusdata.core.Plugin

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
