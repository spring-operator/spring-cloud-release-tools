/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package demo;

import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;

/**
 * @author Spencer Gibb
 */
public class MyService {

	@HystrixCommand(fallbackMethod = "fallback")
	public String ok() {
		return "OK";
	}

	@HystrixCommand(fallbackMethod = "fallback")
	public String fail(boolean throwSomething) {
		if (throwSomething)
			throw new RuntimeException("fail now");
		else
			return "";

	}

	public String fallback() {
		return "from the fallback";
	}

	public String fallback(boolean throwSomething) {
		return "from the fallback";
	}

}