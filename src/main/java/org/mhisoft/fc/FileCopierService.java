/*
 * Copyright (c) 2014- MHISoft LLC and/or its affiliates. All rights reserved.
 * Licensed to MHISoft LLC under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. MHISoft LLC licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.mhisoft.fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.File;
import java.io.FilenameFilter;

import org.mhisoft.fc.ui.UI;

/**
 * Description: walk the directory and schedule workers to copy over files
 *
 * @author Tony Xue
 * @since Oct, 2014
 */
public class FileCopierService {

	//Integer threads;
	boolean lastAnsweredDeleteAll = false;
	boolean initialConfirmation = false;
	private UI rdProUI;
	private FileCopyStatistics statistics;
	private MultiThreadExecutorService fileCopyWorkersPool;
	private MultiThreadExecutorService packageSmallFilesWorkersPool;


	public FileCopierService(UI rdProUI,
			RunTimeProperties props
			, FileCopyStatistics frs
			, MultiThreadExecutorService fileCopyWorkersPool
			,  MultiThreadExecutorService packageSmallFilesWorkersPool
	) {
		this.rdProUI = rdProUI;
		this.fileCopyWorkersPool = fileCopyWorkersPool;
		this.packageSmallFilesWorkersPool = packageSmallFilesWorkersPool;
		this.statistics = frs;
		rdProUI.reset();
	}


	final static long SMALL_FILE_SIZE = 20000;

	public void walkTreeAndCopy(int level, final String[] rootDirs, final String destDir, long targetDirLastModified) {


		String _destDir; //the global target dir

		if (RunTimeProperties.instance.flatCopy) {
			_destDir = RunTimeProperties.instance.getDestDir();
		} else
			_destDir = destDir;

		boolean _destDirCreated = false;

		for (String sRootDir : rootDirs) {

			//the targetDir relative to each root Dir. it changes for each rootDir.
			//scope is within the loop. 
			String _targetDir =  _destDir;

			File rootDir = new File(sRootDir.trim());

			if (RunTimeProperties.instance.isStopThreads()) {
				rdProUI.println("[warn]Cancelled by user. stop walk. ");
				return;
			}



			/*examine the dir*/
			List<File> filesList = new ArrayList<>();
			if (rootDir.isFile()) {
				//the rootDir here is only a file 
				filesList.add(rootDir);
			}
			else {

				File[] childFiles = rootDir.listFiles(new FilenameFilter() {
					@Override
					public boolean accept(File dir, String name) {
						return true; //filters
					}
				});

				if (childFiles!=null)
					filesList = Arrays.asList(childFiles);
				
				rdProUI.showCurrentDir("Copying files under directory: " + rootDir);
			}

			/*nothing under this directory. don't even create the target empty dir*/
			if (filesList.isEmpty())
				return;


			if (!_destDirCreated && !new File(_destDir).exists()) {
				_destDirCreated = true;
				FileUtils.createDir(targetDirLastModified, new File(_destDir), rdProUI, statistics);
			}



			//make the TheSameSourceFolderUnderTarget only once at the top level.
			//use the _targetDir
			if (level == 0 && rootDir.isDirectory()
					&& RunTimeProperties.instance.isCreateTheSameSourceFolderUnderTarget()) {
				//   get the last dir of the source and make it under dest
				//ext  /Users/me/doc --> /Users/me/target make /Users/me/target/doc
				_targetDir = _targetDir + File.separator + rootDir.getName();
				if (!new File(_targetDir).exists())
					FileUtils.createDir(rootDir.lastModified(), new File(_targetDir), rdProUI, statistics);
			}



			//List<File> notQualifiedToPackDirList = new ArrayList<>();
			boolean thisRootDirQualifiedToPack = false;

			/* process files under this "source" dir,  package small files */
			if (RunTimeProperties.instance.isPackageSmallFiles() && rootDir.isDirectory()) {
				DirecotryStat direcotryStat = FileUtils.getDirectoryStats(rootDir, SMALL_FILE_SIZE);

				if (  direcotryStat.isFail()) {
					rdProUI.print(LogLevel.debug, direcotryStat.getFailMsg() +", will not pack this directory.");
					thisRootDirQualifiedToPack = false;
				}
				else{

						if ((direcotryStat.getSmallFileCount() >= 3 //number criteria
								|| (direcotryStat.getSmallFileCount() >= 2 && direcotryStat.getTotalSmallFileSize() >= 4096 / 0.7) //
						) //size criteria


						) {
							thisRootDirQualifiedToPack = true;
							PackageSmallFilesThread t = new PackageSmallFilesThread(rdProUI
									, sRootDir, _targetDir, statistics, fileCopyWorkersPool);
							packageSmallFilesWorkersPool.addTask(t);
						} else
							thisRootDirQualifiedToPack = false;
					}
			}



			/*iterate the child files  of this "rootDir, copy over the reset of the large files*/
			for (File childFile : filesList) {

				if (RunTimeProperties.instance.isStopThreads()) {
					rdProUI.println("[warn]Cancelled by user. stop walk. ", true);
					return;
				}


				//now what's left in the dir are the large files
				if (childFile.isFile()
						&& !childFile.getName().startsWith(RunTimeProperties.zip_prefix) //not my zip
						&& (!thisRootDirQualifiedToPack || childFile.length() > SMALL_FILE_SIZE)) {  //this dir has files I need to copy over

					String newDestFile = _targetDir + File.separator + childFile.getName();
					File targetFile = new File(newDestFile);
					if (!targetFile.exists() || FileUtils.overrideTargetFile(childFile, targetFile)) {
						CopyFileThread t = new CopyFileThread(rdProUI
								, childFile, targetFile, null, statistics);
						fileCopyWorkersPool.addTask(t);
					} else {
						if (RunTimeProperties.instance.isVerbose())
							rdProUI.println(String.format("\tFile %s exists on the target dir, skipped. ", newDestFile));
					}


				}
			}



			/*iterate the child directories of this "rootDir*/
			for (File childDir : filesList) {

				if (RunTimeProperties.instance.isStopThreads()) {
					rdProUI.println("[warn]Cancelled by user. stop walk. ", true);
					return;
				}

				if (childDir.isDirectory()) {

					String targeChildDir = _targetDir + File.separator + childDir.getName();
					walkTreeAndCopy(level + 1, new String[]{childDir.getAbsolutePath()}, targeChildDir, childDir.lastModified());
				}
			}


		}

	}




}
