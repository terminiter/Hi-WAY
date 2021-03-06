/*******************************************************************************
 * In the Hi-WAY project we propose a novel approach of executing scientific
 * workflows processing Big Data, as found in NGS applications, on distributed
 * computational infrastructures. The Hi-WAY software stack comprises the func-
 * tional workflow language Cuneiform as well as the Hi-WAY ApplicationMaster
 * for Apache Hadoop 2.x (YARN).
 *
 * List of Contributors:
 *
 * Hannes Schuh (HU Berlin)
 * Marc Bux (HU Berlin)
 * Jörgen Brandt (HU Berlin)
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
package de.huberlin.hiwaydb.useDB;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.couchbase.client.CouchbaseClient;
import com.couchbase.client.protocol.views.Query;
import com.couchbase.client.protocol.views.View;
import com.couchbase.client.protocol.views.ViewResponse;
import com.couchbase.client.protocol.views.ViewRow;
import com.google.gson.Gson;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;

import de.huberlin.hiwaydb.LogToDB.InvocDoc;
import de.huberlin.hiwaydb.LogToDB.WfRunDoc;
import de.huberlin.hiwaydb.dal.Accesstime;
import de.huberlin.hiwaydb.dal.File;
import de.huberlin.hiwaydb.dal.Hiwayevent;
import de.huberlin.hiwaydb.dal.Inoutput;
import de.huberlin.hiwaydb.dal.Invocation;
import de.huberlin.hiwaydb.dal.Userevent;
import de.huberlin.hiwaydb.dal.Workflowrun;

public class Reader {

	private static SessionFactory dbSessionFactory;
	private static SessionFactory dbSessionFactoryStandard;

	public static void main(String[] args) {

		List<String> fehler = new ArrayList<>();
		String lineIn = "";

		try (BufferedReader test = new BufferedReader(new InputStreamReader(System.in))) {
			System.out.println("Datenbank füllen, Format:AnzahlWFs,schema");
			lineIn = test.readLine();

			if (lineIn != null && lineIn.equalsIgnoreCase("accesstime")) {
				System.out.println("korrektur accesstime...");
				dbSessionFactoryStandard = getSQLSession("messungen");
				Session session = dbSessionFactoryStandard.openSession();
				Transaction tx = null;
				tx = session.beginTransaction();
				session.createQuery("FROM Accesstime at where at.wfName='' or at.funktion='JsonReportEntryToDB'" + " order by at.id desc");
				List<Accesstime> allTimes = new ArrayList<>();
				tx.commit();
				tx = session.beginTransaction();

				String currRunID = "";
				String currentWFName = "";
				int fill = 0;

				for (Accesstime at : allTimes) {
					if (!at.getWfName().equals("") && !at.getWfName().equals("")) {
						currentWFName = at.getWfName();
						currRunID = at.getRunId();
					} else {
						fill++;
						System.out.println("haben KEINEN namen: DIESEN setzen " + currentWFName + " anzahl:" + fill);
						at.setWfName(currentWFName);
						at.setRunId(currRunID);
						session.save(at);
					}
				}

				tx.commit();
				session.close();
				System.out.println("fertig...");
			}

			else if (lineIn != null) {
				int toLimit = Integer.parseInt(lineIn.substring(0, lineIn.lastIndexOf(",")));
				String db = lineIn.substring(lineIn.lastIndexOf(",") + 1, lineIn.lastIndexOf(";"));
				String SqlNosql = lineIn.substring(lineIn.lastIndexOf(";") + 1, lineIn.length());
				if (toLimit < 10) {
					toLimit = 1000;
				}
				int limit = toLimit;

				System.out.println("Datenbanken " + db + "  füllen...bist Limit: " + toLimit);
				Session session = null;

				try {
					if (SqlNosql.equals("nosql")) {
						List<URI> uris = new ArrayList<>();
						uris.add(URI.create("http://192.168.127.43:8091/pools"));

						CouchbaseClient client = new CouchbaseClient(uris, db, "reverse");
						CouchbaseClient firstClient = new CouchbaseClient(uris, "hiwaydb", "reverse");

						int startSize = 0;
						View view = client.getView("Workflow", "WfRunCount");
						Query query = new Query();

						// erst mal den Inhalt aus hiwaydb holen
						ViewResponse result = client.query(view, query);
						for (ViewRow row : result) {
							startSize = Integer.parseInt(row.getValue());
						}
						view = firstClient.getView("Workflow", "WfRunAll");

						query = new Query();
						query.setIncludeDocs(true);

						// erst mal den Inhalt aus hiwaydb holen
						result = firstClient.query(view, query);
						int resultSize = result.size();
						int y = 0;

						while (startSize < limit && SqlNosql.equals("nosql")) {
							y++;
							System.out.println("Couchbase durchgang:" + y + " Anzahl WFs: " + startSize + " addWFs: " + resultSize);

							if (startSize < limit) {
								int back = copyWorkflowNoSQL(result, limit, client, startSize, firstClient);
								startSize = startSize + back;
							}
						}
					}

					if (SqlNosql.equals("sql")) {
						dbSessionFactory = getSQLSession(db);
						dbSessionFactoryStandard = getSQLSession("hiwaydb");

						session = dbSessionFactoryStandard.openSession();
						Transaction tx = null;
						tx = session.beginTransaction();
						org.hibernate.Query querySQL = null;
						querySQL = session.createQuery("FROM Workflowrun");
						List<Workflowrun> allWFs = new ArrayList<>();
						for (Object o : querySQL.list())
							allWFs.add((Workflowrun) o);
						tx.commit();

						Session session2 = dbSessionFactory.openSession();
						tx = session2.beginTransaction();
						querySQL = session2.createQuery("FROM Workflowrun");
						List<Workflowrun> allWFsCurrent = new ArrayList<>();
						for (Object o : querySQL.list())
							allWFsCurrent.add((Workflowrun) o);
						int currentSize = allWFsCurrent.size();
						tx.commit();
						session2.close();

						int newWFs = 0;
						int y = 0;
						while (currentSize < limit && SqlNosql.equals("sql")) {
							y++;
							System.out.println("MySQL durchgang:" + y + " CurrentSize: " + currentSize + " Anzahln neueWFs: " + allWFs.size() + " newWFs : "
									+ newWFs);
							if (currentSize < limit) {
								int added = copyWorkflowsSQL(allWFs, limit, currentSize);
								currentSize = currentSize + added;
								newWFs += added;
							}
							System.out.println("MySQL durchgang:" + y + " fertig! CurrentSize: " + currentSize + " newWFs : " + newWFs + " limit: " + limit);
						}
						session.close();
					}

					System.out.println("fertig");
				} catch (RuntimeException e) {
					throw e; // or display error message
				} finally {
					if (session != null && session.isOpen()) {
						session.close();
					}
				}

			}
			System.out.println("juchei fertig...");

		} catch (Exception e) {

			e.printStackTrace();
		} finally {
			System.out.println("JSONFehler:");
			for (String s : fehler) {
				System.out.println(s + "|");
			}

			System.out.println("Fehler:");
			for (String s : fehler) {
				System.out.println(s + "|");
			}

		}

	}

	private static SessionFactory getSQLSession(String db) {
		try {
			Configuration configuration = new Configuration();
			System.out.println("connect to: " + db);

			configuration.setProperty("hibernate.connection.url", "jdbc:mysql://127.0.0.1/" + db);
			configuration.setProperty("hibernate.connection.username", "root");
			configuration.setProperty("hibernate.connection.password", "reverse");
			configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQLInnoDBDialect");
			configuration.setProperty("hibernate.connection.driver_class", "com.mysql.jdbc.Driver");
			configuration.setProperty("connection.provider_class", "org.hibernate.connection.C3P0ConnectionProvider");
			configuration.setProperty("hibernate.transaction.factory_class", "org.hibernate.transaction.JDBCTransactionFactory");
			configuration.setProperty("hibernate.current_session_context_class", "thread");
			configuration.setProperty("hibernate.initialPoolSize", "20");
			configuration.setProperty("hibernate.c3p0.min_size", "5");
			configuration.setProperty("hibernate.c3p0.max_size", "1000");
			configuration.setProperty("hibernate.maxIdleTime", "3600");
			configuration.setProperty("hibernate.c3p0.maxIdleTimeExcessConnections", "300");
			configuration.setProperty("hibernate.c3p0.timeout", "330");
			configuration.setProperty("hibernate.c3p0.idle_test_period", "300");
			configuration.setProperty("hibernate.c3p0.max_statements", "13000");
			configuration.setProperty("hibernate.c3p0.maxStatementsPerConnection", "30");
			configuration.setProperty("hibernate.c3p0.acquire_increment", "10");
			configuration.addAnnotatedClass(de.huberlin.hiwaydb.dal.Accesstime.class);

			StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder().applySettings(configuration.getProperties());
			SessionFactory sessionFactory = configuration.buildSessionFactory(builder.build());
			return sessionFactory;
		} catch (Throwable ex) {
			System.err.println("Failed to create sessionFactory object." + ex);
			throw new ExceptionInInitializerError(ex);
		}
	}

	private static int copyWorkflowNoSQL(ViewResponse result, int limit, CouchbaseClient client, int currentSize, CouchbaseClient hiwayClient) {
		Gson gson = new Gson();
		WfRunDoc newRun = null;
		WfRunDoc run = null;

		System.out.println("copy NoSQL anzwahl wf:" + currentSize + " resultSize:" + result.size());

		int i = 0;
		// Iterate over the found wf documents
		for (ViewRow row : result) {
			i++;
			if (currentSize + i >= limit + 1) {
				System.out.println("Break and raus: all: " + currentSize + " | i: " + i + " limit: " + limit);
				return i;
			}
			run = gson.fromJson((String) row.getDocument(), WfRunDoc.class);

			if (run.getRunId() != null) {
				String newName = run.getRunId().substring(0, 36) + "_" + Calendar.getInstance().getTimeInMillis();
				System.out.println("Run: " + run.getName() + " , I: " + i + " | " + newName);

				newRun = new WfRunDoc();
				newRun.setRunId(newName);
				newRun.setName(run.getName());
				newRun.setWfTime(run.getWfTime());
				newRun.setHiwayEvent(run.getHiwayEvent());
				newRun.setTaskIDs(run.getTaskIDs());

				client.set(newName, gson.toJson(newRun));

				View view = hiwayClient.getView("Workflow", "WfRunInvocs");
				Query query = new Query();
				query.setIncludeDocs(true).setKey(run.getRunId());

				// Query the Cluster
				ViewResponse newResult = hiwayClient.query(view, query);

				InvocDoc newInvoc = null;
				InvocDoc oldInvoc = null;

				for (ViewRow invocDoc : newResult) {
					oldInvoc = gson.fromJson((String) invocDoc.getDocument(), InvocDoc.class);

					newInvoc = new InvocDoc();
					newInvoc.setHostname(oldInvoc.getHostname());
					newInvoc.setLang(oldInvoc.getLang());
					newInvoc.setRealTime(oldInvoc.getRealTime());
					newInvoc.setRealTimeIn(oldInvoc.getRealTimeIn());
					newInvoc.setRealTimeOut(oldInvoc.getRealTimeOut());
					newInvoc.setRunId(newName);
					newInvoc.setScheduleTime(oldInvoc.getScheduleTime());
					newInvoc.setStandardError(oldInvoc.getStandardError());
					newInvoc.setStandardOut(oldInvoc.getStandardOut());
					newInvoc.setTaskId(oldInvoc.getTaskId());
					newInvoc.setTaskname(oldInvoc.getTaskname());
					newInvoc.setTimestamp(Calendar.getInstance().getTimeInMillis());
					newInvoc.setInvocId(oldInvoc.getInvocId());
					newInvoc.setFiles(oldInvoc.getFiles());
					newInvoc.setInput(oldInvoc.getInput());
					newInvoc.setOutput(oldInvoc.getOutput());
					newInvoc.setUserevents(oldInvoc.getUserevents());

					String invocID = newName + "_" + newInvoc.getInvocId();

					System.out.println("save invoc..." + invocID);
					client.set(invocID, gson.toJson(newInvoc));
				}
			}

		}
		return i;

	}

	private static int copyWorkflowsSQL(List<Workflowrun> allWFs, int limit, int currentSize) {
		Workflowrun newRun = null;
		Set<Hiwayevent> newHiwayevents = null;
		Set<Invocation> newInvocations = null;

		Set<Inoutput> newInoutputs = null;
		Set<File> newfiles = null;
		Set<Userevent> newUserevents = null;
		Invocation newInvoc = null;

		// Session session = dbSessionFactory.openSession();

		Calendar cal = Calendar.getInstance();

		Boolean toCommit = true;
		int i = 0;
		Transaction tx = null;
		Hiwayevent newEvent = null;
		File newFile = null;
		Userevent newUE = null;
		Inoutput newIO = null;

		Session session = dbSessionFactory.openSession();

		tx = session.beginTransaction();

		for (Workflowrun run : allWFs) {

			cal = Calendar.getInstance();
			i++;
			if (currentSize + i >= limit + 1) {
				toCommit = false;
				System.out.println("Break and comitt CZ= " + currentSize + " i=" + i + " LIMIT: " + limit);
				tx.commit();
				if (session.isOpen()) {
					session.close();
				}

				return i;
			}

			if (i >= 3000) {
				toCommit = false;
				System.out.println("Break and comitt wegen 3000er Grenze");
				tx.commit();
				if (session.isOpen()) {
					session.close();
				}
				return i;
			}

			if (i % 500 == 0) {
				System.out.println("comitt in between...........");
				tx.commit();
				System.gc();
				tx = session.beginTransaction();
			}

			String newName = run.getRunId().substring(0, 36) + "_" + cal.getTimeInMillis();
			System.out.println("Run" + run.getId() + " , " + run.getWfName() + " , I: " + i + " | " + newName);
			// run mit allen inhalten kopieren und speichern

			newRun = new Workflowrun();
			newRun.setRunId(newName);
			newRun.setWfName(run.getWfName());
			newRun.setWfTime(run.getWfTime());

			// System.out.println("saven newRun");
			session.save(newRun);
			newHiwayevents = new HashSet<>();

			for (Hiwayevent he : run.getHiwayevents()) {
				newEvent = new Hiwayevent();
				newEvent.setContent(he.getContent());
				newEvent.setType(he.getType());
				newEvent.setWorkflowrun(newRun);

				session.save(newEvent);
				newHiwayevents.add(newEvent);
			}

			if (newHiwayevents.size() > 0) {
				newRun.setHiwayevents(newHiwayevents);
			}

			newInvocations = new HashSet<>();

			for (Invocation invoc : run.getInvocations()) {
				cal = Calendar.getInstance();

				newInvoc = new Invocation();
				newInvoc.setDidOn(cal.getTime());
				newInvoc.setHostname(invoc.getHostname());
				newInvoc.setInvocationId(invoc.getInvocationId());
				newInvoc.setRealTime(invoc.getRealTime());
				newInvoc.setRealTimeIn(invoc.getRealTimeIn());
				newInvoc.setRealTimeOut(invoc.getRealTimeOut());
				newInvoc.setScheduleTime(invoc.getScheduleTime());
				newInvoc.setStandardError(invoc.getStandardError());
				newInvoc.setStandardOut(invoc.getStandardOut());
				newInvoc.setTask(invoc.getTask());
				newInvoc.setTimestamp(cal.getTimeInMillis());
				newInvoc.setWorkflowrun(newRun);
				session.save(newInvoc);

				newUserevents = new HashSet<>();
				for (Userevent ue : invoc.getUserevents()) {
					newUE = new Userevent();
					newUE.setContent(ue.getContent());
					newUE.setInvocation(newInvoc);

					session.save(newUE);
					newUserevents.add(newUE);
				}

				if (newUserevents.size() > 0) {
					newInvoc.setUserevents(newUserevents);
				}

				newInoutputs = new HashSet<>();
				for (Inoutput io : invoc.getInoutputs()) {
					newIO = new Inoutput();
					newIO.setContent(io.getContent());
					newIO.setInvocation(newInvoc);
					newIO.setKeypart(io.getKeypart());
					newIO.setType(io.getType());

					session.save(newIO);
					newInoutputs.add(newIO);
				}

				if (newInoutputs.size() > 0) {
					newInvoc.setInoutputs(newInoutputs);
				}

				newfiles = new HashSet<>();
				for (File file : invoc.getFiles()) {
					newFile = new File();

					newFile.setName(file.getName());
					newFile.setRealTimeIn(file.getRealTimeIn());
					newFile.setRealTimeOut(file.getRealTimeOut());
					newFile.setSize(file.getSize());
					newFile.setInvocation(newInvoc);

					session.save(newFile);
					newfiles.add(newFile);
				}

				if (newfiles.size() > 0) {
					newInvoc.setFiles(newfiles);
				}

				newInvocations.add(newInvoc);
			}

			if (newInvocations.size() > 0) {
				newRun.setInvocations(newInvocations);
			}

			newRun = null;
			newHiwayevents = null;
			newEvent = null;
			newInvocations = null;
			newInvoc = null;
			newUE = null;
			newIO = null;
			newInoutputs = null;
			newfiles = null;
			newUserevents = null;

		}

		if (toCommit) {
			System.out.println("comitt");
			tx.commit();
		}

		if (session.isOpen()) {
			session.close();
		}

		return i;
	}
}
