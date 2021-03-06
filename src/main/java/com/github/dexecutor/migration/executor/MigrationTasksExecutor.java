package com.github.dexecutor.migration.executor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.dexecutor.core.DefaultDexecutor;
import com.github.dexecutor.core.DefaultDexecutorState;
import com.github.dexecutor.core.DefaultExecutionEngine;
import com.github.dexecutor.core.Dexecutor;
import com.github.dexecutor.core.DexecutorConfig;
import com.github.dexecutor.core.DexecutorState;
import com.github.dexecutor.core.ExecutionConfig;
import com.github.dexecutor.core.graph.LevelOrderTraversar;
import com.github.dexecutor.core.graph.StringTraversarAction;
import com.github.dexecutor.core.graph.TraversarAction;
import com.github.dexecutor.core.task.Task;
import com.github.dexecutor.core.task.TaskProvider;
import com.github.dexecutor.migration.oxm.MigrationTask;
import com.github.dexecutor.migration.oxm.MigrationTasks;
import com.github.dexecutor.migration.parser.CompositeTableNameProvider;
import com.github.dexecutor.migration.parser.SQLTableNameProvider;
import com.github.dexecutor.migration.parser.TableNameProvider;
import com.github.dexecutor.support.Lists;

public class MigrationTasksExecutor {

	private static final Logger logger = LoggerFactory.getLogger(MigrationTasksExecutor.class);

	private final Dexecutor<String, String> dexecutor;
	private TableNameProvider tableNameProvider;
	private final Map<String, List<String>> tableToTasksMap = new LinkedHashMap<String, List<String>>();

	public MigrationTasksExecutor(final MigrationTasks tasks, final ExecutorService executorService) {
		this.dexecutor = newDexecutor(tasks, executorService);		
		this.tableNameProvider = newTableNameProvider();
		buildGraph(tasks);		
	}

	private Dexecutor<String, String> newDexecutor(final MigrationTasks tasks, final ExecutorService executorService) {
		DexecutorState<String, String> dexecutorState = new DefaultDexecutorState<>();
		DexecutorConfig<String, String> config = new DexecutorConfig<>(new DefaultExecutionEngine<String, String>(dexecutorState, executorService), newTaskProvider(tasks));
		config.setDexecutorState(dexecutorState);
		return new DefaultDexecutor<String, String>(config);
	}
 
	private TableNameProvider newTableNameProvider() {
		List<TableNameProvider> newProviders = new ArrayList<TableNameProvider>();
		newProviders.add(new SQLTableNameProvider());
		return new CompositeTableNameProvider(newProviders);
	}

	private void buildGraph(final MigrationTasks tasks) {
		for (MigrationTask migrationTask : tasks.getTasks()) {
			List<String> tables = tables(migrationTask);
			constructTaskNode(migrationTask, tables);
			recordTaskDetails(migrationTask, tables);
		}
	}

	private void constructTaskNode(final MigrationTask migrationTask, final List<String> tables) {
		List<String> taskIds = dependentTaskIds(migrationTask, tables);
		if (isDependentTask(taskIds)) {
			processDependentTasks(taskIds, migrationTask.getTaskId());
		} else {
			this.dexecutor.addIndependent(migrationTask.getTaskId());
		}
	}

	private List<String> dependentTaskIds(final MigrationTask migrationTask, final List<String> tables) {
		List<String> result = new ArrayList<String>();

		for (String table : tables) {
			if (this.tableToTasksMap.containsKey(table)) {
				String lastTask = Lists.getLast(this.tableToTasksMap.get(table));
				if (lastTask != null && !lastTask.isEmpty()) {
					result.add(lastTask);					
				}
			}
		}
		return result;
	}

	private boolean isDependentTask(final List<String> taskIds) {
		return !taskIds.isEmpty();
	}

	private void processDependentTasks(final List<String> dependentTaskIds, final String currentTaskId) {
		for (String dependentTaskId : dependentTaskIds) {
			this.dexecutor.addDependency(dependentTaskId, currentTaskId);
		}
	}

	private void recordTaskDetails(final MigrationTask migrationTask, final List<String> tables) {
		for (String table : tables) {
			List<String> tasks = processedTasks(table);
			tasks.add(migrationTask.getTaskId());
			this.tableToTasksMap.put(table, tasks);			
		}		
	}

	private List<String> processedTasks(final String table) {
		if (this.tableToTasksMap.containsKey(table)) {
			return this.tableToTasksMap.get(table);
		} else {
			return new ArrayList<String>();			
		}
	}

	private List<String> tables(final MigrationTask migrationTask) {
		return this.tableNameProvider.provideTableNames(migrationTask.getTask());
	}

	public void execute() {
		printGraph();
		this.dexecutor.execute(new ExecutionConfig().immediateRetrying(1));
	}

	private void printGraph() {
		StringBuilder builder = new  StringBuilder();
		TraversarAction<String, String> action = new StringTraversarAction<>(builder);
		this.dexecutor.print(new LevelOrderTraversar<String, String>(), action);
		System.err.println(builder.toString());
	}

	private TaskProvider<String, String> newTaskProvider(final MigrationTasks tasks) {
		return new DataMigrationTaskProvider(tasks);
	}

	private class DataMigrationTaskProvider implements TaskProvider<String, String> {

		public DataMigrationTaskProvider(final MigrationTasks tasks) {

		}

		public Task<String, String> provideTask(final String id) {
			return new DummyTask(id);
		}		
	}

	private static class DummyTask extends Task<String, String> {

		private static final long serialVersionUID = 1L;

		public DummyTask(final String id) {
			setId(id);
		}

		@Override
		public String execute() {
			logger.info("Executing Task {}", getId());
			return getId();
		}		
	}
}
