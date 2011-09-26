/**
 * Copyright (c) 2008-2009 Mark Logic Corporation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * The use of the Apache License does not indicate that this project is
 * affiliated with the Apache Software Foundation.
 */
package com.marklogic.recordloader;

import com.marklogic.ps.SimpleLogger;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author Michael Blakeley, michael.blakeley@marklogic.com
 *
 */
public interface InputHandlerInterface {

    /**
     * @throws LoaderException 
     * 
     */
    public void run() throws LoaderException;

    /**
     * @param _logger
     */
    public void setLogger(SimpleLogger _logger);

    /**
     * @param _config
     */
    public void setConfiguration(Configuration _config);

    /**
     * @param _inputs
     */
    public void setInputs(String[] _inputs);

    /**
     * @param pool
     */
    public void setPool(ThreadPoolExecutor pool);

    /**
     * @param monitor
     */
    public void setMonitor(Monitor monitor);

}
