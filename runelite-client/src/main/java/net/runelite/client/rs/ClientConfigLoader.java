/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
 * Copyright (c) 2018, Tomas Slusny <slusnucky@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.rs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@AllArgsConstructor
@Slf4j
class ClientConfigLoader
{
	private final OkHttpClient okHttpClient;

	RSConfig fetch(HttpUrl url) throws IOException
	{
		final Request request = new Request.Builder()
			.url(url)
			.build();

		RSConfig config = new RSConfig();

		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				throw new IOException("Unsuccessful response: " + response.message());
			}

			parseConfig(new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8)), config);
		}

		// --- AEROSCAPE: ensure critical params are always present ---
		ensureAeroscapeDefaults(config);

		return config;
	}

	/**
	 * Load config from the embedded aeroscape_jav_config.ws resource.
	 * Used as an ultimate fallback when the server is unreachable.
	 */
	RSConfig fetchEmbedded() throws IOException
	{
		RSConfig config = new RSConfig();
		try (InputStream is = getClass().getResourceAsStream("aeroscape_jav_config.ws"))
		{
			if (is == null)
			{
				throw new IOException("Embedded aeroscape_jav_config.ws not found");
			}
			parseConfig(new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)), config);
		}
		log.info("Loaded embedded AeroScape jav_config");
		return config;
	}

	private static void parseConfig(BufferedReader in, RSConfig config) throws IOException
	{
		String str;
		while ((str = in.readLine()) != null)
		{
			int idx = str.indexOf('=');

			if (idx == -1)
			{
				continue;
			}

			String s = str.substring(0, idx);

			switch (s)
			{
				case "param":
					str = str.substring(idx + 1);
					idx = str.indexOf('=');
					s = str.substring(0, idx);

					config.getAppletProperties().put(s, str.substring(idx + 1));
					break;
				case "msg":
					// ignore
					break;
				default:
					config.getClassLoaderProperties().put(s, str.substring(idx + 1));
					break;
			}
		}
	}

	/**
	 * Ensure critical AeroScape params are present. If the server config
	 * is missing any of these, inject the defaults so the client doesn't NPE.
	 */
	private static void ensureAeroscapeDefaults(RSConfig config)
	{
		Map<String, String> params = config.getAppletProperties();

		// param=7=0 is the GameBuild param — missing this causes the NPE
		params.putIfAbsent("7", "0");
		params.putIfAbsent("25", "236");
		params.putIfAbsent("9", "ElZAIrq5NpKN6D3mDdihco3oPeYN2KFy2DCquj7JMmECPmLrDP3Bnw");
		params.putIfAbsent("12", "1");
		params.putIfAbsent("4", "1");
		params.putIfAbsent("10", "0");
		params.putIfAbsent("3", "false");
		params.putIfAbsent("8", "true");
		params.putIfAbsent("13", "play.aeroverra.com");
		params.putIfAbsent("17", "http://play.aeroverra.com/worldlist.ws");
		params.putIfAbsent("6", "0");
		params.putIfAbsent("14", "0");
		params.putIfAbsent("15", "0");
		params.putIfAbsent("16", "false");
		params.putIfAbsent("5", "0");
		params.putIfAbsent("21", "0");

		log.info("AeroScape params ensured: param7(GameBuild)={}, param25(rev)={}, param12(world)={}",
			params.get("7"), params.get("25"), params.get("12"));
	}
}
