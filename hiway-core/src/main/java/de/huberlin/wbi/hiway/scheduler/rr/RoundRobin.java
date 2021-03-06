/*******************************************************************************
 * In the Hi-WAY project we propose a novel approach of executing scientific
 * workflows processing Big Data, as found in NGS applications, on distributed
 * computational infrastructures. The Hi-WAY software stack comprises the func-
 * tional workflow language Cuneiform as well as the Hi-WAY ApplicationMaster
 * for Apache Hadoop 2.x (YARN).
 *
 * List of Contributors:
 *
 * Marc Bux (HU Berlin)
 * Jörgen Brandt (HU Berlin)
 * Hannes Schuh (HU Berlin)
 * Ulf Leser (HU Berlin)
 *
 * Jörgen Brandt is funded by the European Commission through the BiobankCloud
 * project. Marc Bux is funded by the Deutsche Forschungsgemeinschaft through
 * research training group SOAMED (GRK 1651).
 *
 * Copyright 2014 Humboldt-Universität zu Berlin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package de.huberlin.wbi.hiway.scheduler.rr;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.hadoop.fs.FileSystem;

import de.huberlin.wbi.hiway.common.HiWayConfiguration;
import de.huberlin.wbi.hiway.common.TaskInstance;
import de.huberlin.wbi.hiway.scheduler.DepthComparator;
import de.huberlin.wbi.hiway.scheduler.StaticScheduler;

/**
 * The static round robin scheduler that traverses the workflow from the beginning to the end, assigning tasks to compute resources in turn.
 * 
 * @author Marc Bux
 * 
 */
public class RoundRobin extends StaticScheduler {

	private Iterator<String> nodeIterator;

	public RoundRobin(String workflowName, FileSystem hdfs, HiWayConfiguration conf) {
		super(workflowName, hdfs, conf);
	}

	@Override
	protected void addTask(TaskInstance task) {
		numberOfRemainingTasks++;
		if (!nodeIterator.hasNext()) {
			nodeIterator = queues.keySet().iterator();
		}
		String node = nodeIterator.next();
		schedule.put(task, node);
		System.out.println("Task " + task + " scheduled on node " + node);
		if (task.readyToExecute()) {
			addTaskToQueue(task);
		}
	}

	@Override
	public void addTasks(Collection<TaskInstance> tasks) {
		if (nodeIterator == null) {
			nodeIterator = queues.keySet().iterator();
		}
		List<TaskInstance> taskList = new LinkedList<>(tasks);
		Collections.sort(taskList, new DepthComparator());
		super.addTasks(taskList);
	}

}
