package kohgylw.kiftd.server.service.impl;

import kohgylw.kiftd.server.service.*;

import org.springframework.stereotype.*;
import kohgylw.kiftd.server.mapper.*;
import javax.annotation.*;
import kohgylw.kiftd.server.enumeration.*;
import kohgylw.kiftd.server.exception.FoldersTotalOutOfLimitException;
import kohgylw.kiftd.server.listener.ServerInitListener;
import kohgylw.kiftd.server.model.*;
import kohgylw.kiftd.server.pojo.CheckImportFolderRespons;
import kohgylw.kiftd.server.pojo.CheckUploadFilesRespons;

import org.springframework.web.multipart.*;

import javax.servlet.http.*;
import java.io.*;
import java.nio.charset.Charset;

import kohgylw.kiftd.server.util.*;
import java.util.*;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

/**
 * 
 * <h2>文件服务功能实现类</h2>
 * <p>
 * 该类负责对文件相关的服务进行实现操作，例如下载和上传等，各方法功能详见接口定义。
 * </p>
 * 

 * @version 1.0
 * @see kohgylw.kiftd.server.service.FileService
 */
@Service
public class FileServiceImpl extends RangeFileStreamWriter implements FileService {
	private static final String FOLDERS_TOTAL_OUT_OF_LIMIT = "foldersTotalOutOfLimit";// 文件夹数量超限标识
	private static final String FILES_TOTAL_OUT_OF_LIMIT = "filesTotalOutOfLimit";// 文件数量超限标识
	private static final String ERROR_PARAMETER = "errorParameter";// 参数错误标识
	private static final String NO_AUTHORIZED = "noAuthorized";// 权限错误标识
	private static final String UPLOADSUCCESS = "uploadsuccess";// 上传成功标识
	private static final String UPLOADERROR = "uploaderror";// 上传失败标识

	@Resource
	private NodeMapper fm;
	@Resource
	private FolderMapper flm;
	@Resource
	private LogUtil lu;
	@Resource
	private Gson gson;
	@Resource
	private FileBlockUtil fbu;
	@Resource
	private FolderUtil fu;

	private static final String CONTENT_TYPE = "application/octet-stream";

	// 检查上传文件列表的实现（上传文件的前置操作）
	public String checkUploadFile(final HttpServletRequest request, final HttpServletResponse response) {
		final String account = (String) request.getSession().getAttribute("ACCOUNT");
		final String folderId = request.getParameter("folderId");
		final String nameList = request.getParameter("namelist");
		final String maxUploadFileSize = request.getParameter("maxSize");
		final String maxUploadFileIndex = request.getParameter("maxFileIndex");
		// 目标文件夹合法性检查
		if (folderId == null || folderId.length() == 0) {
			return ERROR_PARAMETER;
		}
		// 获取上传目标文件夹，如果没有直接返回错误
		Folder folder = flm.queryById(folderId);
		if (folder == null) {
			return ERROR_PARAMETER;
		}
		// 权限检查
		if (!ConfigureReader.instance().authorized(account, AccountAuth.UPLOAD_FILES, fu.getAllFoldersId(folderId))
				|| !ConfigureReader.instance().accessFolder(folder, account)) {
			return NO_AUTHORIZED;
		}
		// 获得上传文件名列表
		final List<String> namelistObj = gson.fromJson(nameList, new TypeToken<List<String>>() {
		}.getType());
		// 准备一个检查结果对象
		CheckUploadFilesRespons cufr = new CheckUploadFilesRespons();
		// 开始文件上传体积限制检查
		try {
			// 获取最大文件体积（以Byte为单位）
			long mufs = Long.parseLong(maxUploadFileSize);
			// 获取最大文件的名称
			String mfname = namelistObj.get(Integer.parseInt(maxUploadFileIndex));
			long pMaxUploadSize = ConfigureReader.instance().getUploadFileSize(account);
			if (pMaxUploadSize >= 0) {
				if (mufs > pMaxUploadSize) {
					cufr.setCheckResult("fileTooLarge");
					cufr.setMaxUploadFileSize(formatMaxUploadFileSize(pMaxUploadSize));
					cufr.setOverSizeFile(mfname);
					return gson.toJson(cufr);
				}
			}
		} catch (Exception e) {
			return ERROR_PARAMETER;
		}
		// 开始文件命名冲突检查
		final List<String> pereFileNameList = new ArrayList<>();
		// 查找目标目录下是否存在与待上传文件同名的文件（或文件夹），如果有，记录在上方的列表中
		for (final String fileName : namelistObj) {
			if (folderId == null || folderId.length() <= 0 || fileName == null || fileName.length() <= 0) {
				return ERROR_PARAMETER;
			}
			final List<Node> files = this.fm.queryByParentFolderId(folderId);
			if (files.stream().parallel().anyMatch((n) -> n.getFileName()
					.equals(new String(fileName.getBytes(Charset.forName("UTF-8")), Charset.forName("UTF-8"))))) {
				pereFileNameList.add(fileName);
			}
		}
		// 判断如果上传了这一批文件的话，会不会引起文件数量超限
		long estimatedTotal = fm.countByParentFolderId(folderId) - pereFileNameList.size() + namelistObj.size();
		if (estimatedTotal > FileNodeUtil.MAXIMUM_NUM_OF_SINGLE_FOLDER || estimatedTotal < 0) {
			return "filesTotalOutOfLimit";
		}
		// 如果存在同名文件，则写入同名文件的列表；否则，直接允许上传
		if (pereFileNameList.size() > 0) {
			cufr.setCheckResult("hasExistsNames");
			cufr.setPereFileNameList(pereFileNameList);
		} else {
			cufr.setCheckResult("permitUpload");
			cufr.setPereFileNameList(new ArrayList<String>());
		}
		return gson.toJson(cufr);// 以JSON格式写回该结果
	}

	// 格式化存储体积，便于返回上传文件体积的检查提示信息
	private String formatMaxUploadFileSize(long size) {
		double result = (double) size;
		String unit = "B";
		if (size <= 0) {
			return "设置无效，请联系管理员";
		}
		if (size >= 1024 && size < 1048576) {
			result = (double) size / 1024;
			unit = "KB";
		} else if (size >= 1048576 && size < 1073741824) {
			result = (double) size / 1048576;
			unit = "MB";
		} else if (size >= 1073741824) {
			result = (double) size / 1073741824;
			unit = "GB";
		}
		return String.format("%.1f", result) + " " + unit;
	}

	// 执行上传操作，接收文件并存入文件节点
	public String doUploadFile(final HttpServletRequest request, final HttpServletResponse response,
			final MultipartFile file) {
		String account = (String) request.getSession().getAttribute("ACCOUNT");
		final String folderId = request.getParameter("folderId");
		final String originalFileName = new String(file.getOriginalFilename().getBytes(Charset.forName("UTF-8")),
				Charset.forName("UTF-8"));
		String fileName = originalFileName;
		final String repeType = request.getParameter("repeType");
		// 再次检查上传文件名与目标目录ID
		if (folderId == null || folderId.length() <= 0 || originalFileName == null || originalFileName.length() <= 0) {
			return UPLOADERROR;
		}
		Folder folder = flm.queryById(folderId);
		if (folder == null) {
			return UPLOADERROR;
		}
		// 检查上传权限
		if (!ConfigureReader.instance().authorized(account, AccountAuth.UPLOAD_FILES, fu.getAllFoldersId(folderId))
				|| !ConfigureReader.instance().accessFolder(folder, account)) {
			return UPLOADERROR;
		}
		// 检查上传文件体积是否超限
		long mufs = ConfigureReader.instance().getUploadFileSize(account);
		if (mufs >= 0 && file.getSize() > mufs) {
			return UPLOADERROR;
		}
		// 检查是否存在同名文件。不存在：直接存入新节点；存在：检查repeType代表的上传类型：覆盖、跳过、保留两者。
		final List<Node> files = this.fm.queryByParentFolderId(folderId);
		if (files.parallelStream().anyMatch((e) -> e.getFileName().equals(originalFileName))) {
			// 针对存在同名文件的操作
			if (repeType != null) {
				switch (repeType) {
				// 跳过则忽略上传请求并直接返回上传成功（跳过不应上传）
				case "skip":
					return UPLOADSUCCESS;
				// 覆盖则找到已存在文件节点的File并将新内容写入其中，同时更新原节点信息（除了文件名、父目录和ID之外的全部信息）
				case "cover":
					// 其中覆盖操作同时要求用户必须具备删除权限
					if (!ConfigureReader.instance().authorized(account, AccountAuth.DELETE_FILE_OR_FOLDER,
							fu.getAllFoldersId(folderId))) {
						return UPLOADERROR;
					}
					for (Node f : files) {
						if (f.getFileName().equals(originalFileName)) {
							File block = fbu.getFileFromBlocks(f);
							try {
								file.transferTo(block);
								f.setFileSize(fbu.getFileSize(file));
								f.setFileCreationDate(ServerTimeUtil.accurateToDay());
								if (account != null) {
									f.setFileCreator(account);
								} else {
									f.setFileCreator("\u533f\u540d\u7528\u6237");
								}
								if (fm.update(f) > 0) {
									if (isValidNode(f)) {
										this.lu.writeUploadFileEvent(request, f, account);
										return UPLOADSUCCESS;
									} else {
										block.delete();
									}
								}
								return UPLOADERROR;
							} catch (Exception e) {
								return UPLOADERROR;
							}
						}
					}
					return UPLOADERROR;
				// 保留两者，使用型如“xxxxx (n).xx”的形式命名新文件。其中n为计数，例如已经存在2个文件，则新文件的n记为2
				case "both":
					// 设置新文件名为标号形式
					fileName = FileNodeUtil.getNewNodeName(originalFileName, files);
					break;
				default:
					// 其他声明，容错，暂无效果
					return UPLOADERROR;
				}
			} else {
				// 如果既有重复文件、同时又没声明如何操作，则直接上传失败。
				return UPLOADERROR;
			}
		}
		// 判断该文件夹内的文件数量是否超限
		if (fm.countByParentFolderId(folderId) >= FileNodeUtil.MAXIMUM_NUM_OF_SINGLE_FOLDER) {
			return FILES_TOTAL_OUT_OF_LIMIT;
		}
		// 将文件存入节点并获取其存入生成路径，型如“UUID.block”形式。
		final File block = this.fbu.saveToFileBlocks(file);
		if (block == null) {
			return UPLOADERROR;
		}
		final String fsize = this.fbu.getFileSize(file);
		final Node f2 = new Node();
		f2.setFileId(UUID.randomUUID().toString());
		if (account != null) {
			f2.setFileCreator(account);
		} else {
			f2.setFileCreator("\u533f\u540d\u7528\u6237");
		}
		f2.setFileCreationDate(ServerTimeUtil.accurateToDay());
		f2.setFileName(fileName);
		f2.setFileParentFolder(folderId);
		f2.setFilePath(block.getName());
		f2.setFileSize(fsize);
		int i = 0;
		// 尽可能避免UUID重复的情况发生，重试10次
		while (true) {
			try {
				if (this.fm.insert(f2) > 0) {
					if (isValidNode(f2)) {
						this.lu.writeUploadFileEvent(request, f2, account);
						return UPLOADSUCCESS;
					} else {
						block.delete();
						return UPLOADERROR;
					}
				}
				break;
			} catch (Exception e) {
				f2.setFileId(UUID.randomUUID().toString());
				i++;
			}
			if (i >= 10) {
				break;
			}
		}
		return UPLOADERROR;
	}

	// 删除单个文件，该功能与删除多个文件重复，计划合并二者
	public String deleteFile(final HttpServletRequest request) {
		// 接收参数并接续要删除的文件
		final String fileId = request.getParameter("fileId");
		final String account = (String) request.getSession().getAttribute("ACCOUNT");
		if (fileId == null || fileId.length() <= 0) {
			return ERROR_PARAMETER;
		}
		// 确认要删除的文件存在
		final Node file = this.fm.queryById(fileId);
		if (file == null) {
			return "deleteFileSuccess";
		}
		final Folder f = this.flm.queryById(file.getFileParentFolder());
		// 权限检查
		if (!ConfigureReader.instance().authorized(account, AccountAuth.DELETE_FILE_OR_FOLDER,
				fu.getAllFoldersId(file.getFileParentFolder()))
				|| !ConfigureReader.instance().accessFolder(f, account)) {
			return NO_AUTHORIZED;
		}
		// 从文件块删除
		if (!this.fbu.deleteFromFileBlocks(file)) {
			return "cannotDeleteFile";
		}
		// 从节点删除
		if (this.fm.deleteById(fileId) > 0) {
			this.lu.writeDeleteFileEvent(request, file);
			return "deleteFileSuccess";
		}
		return "cannotDeleteFile";
	}

	// 普通下载：下载单个文件
	public void doDownloadFile(final HttpServletRequest request, final HttpServletResponse response) {
		final String account = (String) request.getSession().getAttribute("ACCOUNT");
		// 权限检查
		// 找到要下载的文件节点
		final String fileId = request.getParameter("fileId");
		if (fileId != null) {
			final Node f = this.fm.queryById(fileId);
			if (f != null) {
				if (ConfigureReader.instance().authorized(account, AccountAuth.DOWNLOAD_FILES,
						fu.getAllFoldersId(f.getFileParentFolder()))) {
					Folder folder = flm.queryById(f.getFileParentFolder());
					if (ConfigureReader.instance().accessFolder(folder, account)) {
						// 执行写出
						final File fo = this.fbu.getFileFromBlocks(f);
						if (fo != null) {
							writeRangeFileStream(request, response, fo, f.getFileName(), CONTENT_TYPE,
									ConfigureReader.instance().getDownloadMaxRate(account));
							// 日志记录（仅针对一次下载）
							if (request.getHeader("Range") == null) {
								this.lu.writeDownloadFileEvent(request, f);
							}
							return;
						}
					}
				}
			}
		}
		try {
			//  处理无法下载的资源
			response.sendError(404);
		} catch (IOException e) {
		}
	}

	// 重命名文件
	public String doRenameFile(final HttpServletRequest request) {
		final String fileId = request.getParameter("fileId");
		final String newFileName = request.getParameter("newFileName");
		final String account = (String) request.getSession().getAttribute("ACCOUNT");
		// 参数检查
		if (fileId == null || fileId.length() <= 0 || newFileName == null || newFileName.length() <= 0) {
			return ERROR_PARAMETER;
		}
		if (!TextFormateUtil.instance().matcherFileName(newFileName) || newFileName.indexOf(".") == 0) {
			return ERROR_PARAMETER;
		}
		final Node file = this.fm.queryById(fileId);
		if (file == null) {
			return ERROR_PARAMETER;
		}
		final Folder folder = flm.queryById(file.getFileParentFolder());
		if (!ConfigureReader.instance().accessFolder(folder, account)) {
			return NO_AUTHORIZED;
		}
		// 权限检查
		if (!ConfigureReader.instance().authorized(account, AccountAuth.RENAME_FILE_OR_FOLDER,
				fu.getAllFoldersId(file.getFileParentFolder()))) {
			return NO_AUTHORIZED;
		}
		if (!file.getFileName().equals(newFileName)) {
			// 不允许重名
			if (fm.queryBySomeFolder(fileId).parallelStream().anyMatch((e) -> e.getFileName().equals(newFileName))) {
				return "nameOccupied";
			}
			// 更新文件名
			final Map<String, String> map = new HashMap<String, String>();
			map.put("fileId", fileId);
			map.put("newFileName", newFileName);
			if (this.fm.updateFileNameById(map) == 0) {
				// 并写入日志
				return "cannotRenameFile";
			}
		}
		this.lu.writeRenameFileEvent(request, file, newFileName);
		return "renameFileSuccess";
	}

	// 删除所有选中文件和文件夹
	public String deleteCheckedFiles(final HttpServletRequest request) {
		final String strIdList = request.getParameter("strIdList");
		final String strFidList = request.getParameter("strFidList");
		final String account = (String) request.getSession().getAttribute("ACCOUNT");
		try {
			// 得到要删除的文件ID列表
			final List<String> idList = gson.fromJson(strIdList, new TypeToken<List<String>>() {
			}.getType());
			// 对每个要删除的文件节点进行确认并删除
			for (final String fileId : idList) {
				if (fileId == null || fileId.length() == 0) {
					return ERROR_PARAMETER;
				}
				final Node file = this.fm.queryById(fileId);
				if (file == null) {
					continue;
				}
				final Folder folder = flm.queryById(file.getFileParentFolder());
				if (!ConfigureReader.instance().accessFolder(folder, account)) {
					return NO_AUTHORIZED;
				}
				if (!ConfigureReader.instance().authorized(account, AccountAuth.DELETE_FILE_OR_FOLDER,
						fu.getAllFoldersId(file.getFileParentFolder()))) {
					return NO_AUTHORIZED;
				}
				// 删除文件块
				if (!this.fbu.deleteFromFileBlocks(file)) {
					return "cannotDeleteFile";
				}
				// 删除文件节点
				if (this.fm.deleteById(fileId) <= 0) {
					return "cannotDeleteFile";
				}
				// 日志记录
				this.lu.writeDeleteFileEvent(request, file);
			}
			// 删完选中的文件，再去删文件夹
			final List<String> fidList = gson.fromJson(strFidList, new TypeToken<List<String>>() {
			}.getType());
			for (String fid : fidList) {
				Folder folder = flm.queryById(fid);
				if (folder == null) {
					continue;
				}
				if (!ConfigureReader.instance().accessFolder(folder, account)) {
					return NO_AUTHORIZED;
				}
				if (!ConfigureReader.instance().authorized(account, AccountAuth.DELETE_FILE_OR_FOLDER,
						fu.getAllFoldersId(folder.getFolderParent()))) {
					return NO_AUTHORIZED;
				}
				final List<Folder> l = this.fu.getParentList(fid);
				if (fu.deleteAllChildFolder(fid) <= 0) {
					return "cannotDeleteFile";
				} else {
					this.lu.writeDeleteFolderEvent(request, folder, l);
				}
			}
			if (fidList.size() > 0) {
				ServerInitListener.needCheck = true;
			}
			return "deleteFileSuccess";
		} catch (Exception e) {
			return ERROR_PARAMETER;
		}
	}

	// 打包下载功能：前置——压缩要打包下载的文件
	public String downloadCheckedFiles(final HttpServletRequest request) {
		if (ConfigureReader.instance().isEnableDownloadByZip()) {
			final String account = (String) request.getSession().getAttribute("ACCOUNT");
			final String strIdList = request.getParameter("strIdList");
			final String strFidList = request.getParameter("strFidList");
			try {
				// 获得要打包下载的文件ID
				final List<String> idList = gson.fromJson(strIdList, new TypeToken<List<String>>() {
				}.getType());
				final List<String> fidList = gson.fromJson(strFidList, new TypeToken<List<String>>() {
				}.getType());
				// 创建ZIP压缩包并将全部文件压缩
				if (idList.size() > 0 || fidList.size() > 0) {
					final String zipname = this.fbu.createZip(idList, fidList, account);
					this.lu.writeDownloadCheckedFileEvent(request, idList);
					// 返回生成的压缩包路径
					return zipname;
				}
			} catch (Exception ex) {
				lu.writeException(ex);
			}
		}
		return "ERROR";
	}

	// 打包下载功能：执行——下载压缩好的文件
	public void downloadCheckedFilesZip(final HttpServletRequest request, final HttpServletResponse response)
			throws Exception {
		final String zipname = request.getParameter("zipId");
		final String account = (String) request.getSession().getAttribute("ACCOUNT");
		if (zipname != null && !zipname.equals("ERROR")) {
			final String tfPath = ConfigureReader.instance().getTemporaryfilePath();
			final File zip = new File(tfPath, zipname);
			String fname = "kiftd_" + ServerTimeUtil.accurateToDay() + "_\u6253\u5305\u4e0b\u8f7d.zip";
			if (zip.exists()) {
				writeRangeFileStream(request, response, zip, fname, CONTENT_TYPE,
						ConfigureReader.instance().getDownloadMaxRate(account));
				zip.delete();
			}
		}
	}

	public String getPackTime(final HttpServletRequest request) {
		if (ConfigureReader.instance().isEnableDownloadByZip()) {
			final String account = (String) request.getSession().getAttribute("ACCOUNT");
			final String strIdList = request.getParameter("strIdList");
			final String strFidList = request.getParameter("strFidList");
			try {
				final List<String> idList = gson.fromJson(strIdList, new TypeToken<List<String>>() {
				}.getType());
				final List<String> fidList = gson.fromJson(strFidList, new TypeToken<List<String>>() {
				}.getType());
				for (String fid : fidList) {
					countFolderFilesId(account, fid, fidList);
				}
				long packTime = 0L;
				for (final String fid : idList) {
					final Node n = this.fm.queryById(fid);
					if (ConfigureReader.instance().authorized(account, AccountAuth.DOWNLOAD_FILES,
							fu.getAllFoldersId(n.getFileParentFolder()))
							&& ConfigureReader.instance().accessFolder(flm.queryById(n.getFileParentFolder()),
									account)) {
						final File f = fbu.getFileFromBlocks(n);
						if (f != null && f.exists()) {
							packTime += f.length() / 25000000L;
						}
					}
				}
				if (packTime < 4L) {
					return "\u9a6c\u4e0a\u5b8c\u6210";
				}
				if (packTime >= 4L && packTime < 10L) {
					return "\u5927\u7ea610\u79d2";
				}
				if (packTime >= 10L && packTime < 35L) {
					return "\u4e0d\u5230\u534a\u5206\u949f";
				}
				if (packTime >= 35L && packTime < 65L) {
					return "\u5927\u7ea61\u5206\u949f";
				}
				if (packTime >= 65L) {
					return "\u8d85\u8fc7" + packTime / 60L
							+ "\u5206\u949f\uff0c\u8017\u65f6\u8f83\u957f\uff0c\u5efa\u8bae\u76f4\u63a5\u4e0b\u8f7d";
				}
			} catch (Exception ex) {
				lu.writeException(ex);
			}
		}
		return "0";
	}

	// 用于迭代获得全部文件夹内的文件ID（方便预测耗时）
	private void countFolderFilesId(String account, String fid, List<String> idList) {
		Folder f = flm.queryById(fid);
		if (ConfigureReader.instance().accessFolder(f, account)) {
			idList.addAll(Arrays.asList(
					fm.queryByParentFolderId(fid).parallelStream().map((e) -> e.getFileId()).toArray(String[]::new)));
			List<Folder> cFolders = flm.queryByParentId(fid);
			for (Folder cFolder : cFolders) {
				countFolderFilesId(account, cFolder.getFolderId(), idList);
			}
		}
	}

	// 执行移动文件操作
	@Override
	public String doMoveFiles(HttpServletRequest request) {
		final String strIdList = request.getParameter("strIdList");
		final String strFidList = request.getParameter("strFidList");
		final String strOptMap = request.getParameter("strOptMap");
		final String locationpath = request.getParameter("locationpath");
		final String account = (String) request.getSession().getAttribute("ACCOUNT");
		Folder targetFolder = flm.queryById(locationpath);
		if (targetFolder == null) {
			return ERROR_PARAMETER;
		}
		if (!ConfigureReader.instance().accessFolder(targetFolder, account)) {
			return NO_AUTHORIZED;
		}
		if (!ConfigureReader.instance().authorized(account, AccountAuth.MOVE_FILES, fu.getAllFoldersId(locationpath))) {
			return NO_AUTHORIZED;
		}
		try {
			final List<String> idList = gson.fromJson(strIdList, new TypeToken<List<String>>() {
			}.getType());
			final Map<String, String> optMap = gson.fromJson(strOptMap, new TypeToken<Map<String, String>>() {
			}.getType());
			for (final String id : idList) {
				if (id == null || id.length() <= 0) {
					return ERROR_PARAMETER;
				}
				final Node node = this.fm.queryById(id);
				if (node == null) {
					return ERROR_PARAMETER;
				}
				if (node.getFileParentFolder().equals(locationpath)) {
					continue;
				}
				if (!ConfigureReader.instance().accessFolder(flm.queryById(node.getFileParentFolder()), account)) {
					return NO_AUTHORIZED;
				}
				if (!ConfigureReader.instance().authorized(account, AccountAuth.MOVE_FILES,
						fu.getAllFoldersId(node.getFileParentFolder()))) {
					return NO_AUTHORIZED;
				}
				if (fm.queryByParentFolderId(locationpath).parallelStream()
						.anyMatch((e) -> e.getFileName().equals(node.getFileName()))) {
					if (optMap.get(id) == null) {
						return ERROR_PARAMETER;
					}
					switch (optMap.get(id)) {
					case "cover":
						if (!ConfigureReader.instance().authorized(account, AccountAuth.DELETE_FILE_OR_FOLDER,
								fu.getAllFoldersId(locationpath))) {
							return NO_AUTHORIZED;
						}
						Node n = fm.queryByParentFolderId(locationpath).parallelStream()
								.filter((e) -> e.getFileName().equals(node.getFileName())).findFirst().get();
						if (fm.deleteById(n.getFileId()) > 0) {
							Map<String, String> map = new HashMap<>();
							map.put("fileId", node.getFileId());
							map.put("locationpath", locationpath);
							if (this.fm.moveById(map) <= 0) {
								return "cannotMoveFiles";
							}
						} else {
							return "cannotMoveFiles";
						}
						this.lu.writeMoveFileEvent(request, node);
						break;
					case "both":
						if (fm.countByParentFolderId(locationpath) >= FileNodeUtil.MAXIMUM_NUM_OF_SINGLE_FOLDER) {
							return FILES_TOTAL_OUT_OF_LIMIT;
						}
						node.setFileName(FileNodeUtil.getNewNodeName(node.getFileName(),
								fm.queryByParentFolderId(locationpath)));
						if (fm.update(node) <= 0) {
							return "cannotMoveFiles";
						}
						Map<String, String> map = new HashMap<>();
						map.put("fileId", node.getFileId());
						map.put("locationpath", locationpath);
						if (this.fm.moveById(map) <= 0) {
							return "cannotMoveFiles";
						}
						this.lu.writeMoveFileEvent(request, node);
						break;
					case "skip":
						break;
					default:
						return ERROR_PARAMETER;
					}
				} else {
					if (fm.countByParentFolderId(locationpath) >= FileNodeUtil.MAXIMUM_NUM_OF_SINGLE_FOLDER) {
						return FILES_TOTAL_OUT_OF_LIMIT;
					}
					Map<String, String> map = new HashMap<>();
					map.put("fileId", node.getFileId());
					map.put("locationpath", locationpath);
					if (this.fm.moveById(map) <= 0) {
						return "cannotMoveFiles";
					}
					this.lu.writeMoveFileEvent(request, node);
				}
			}
			final List<String> fidList = gson.fromJson(strFidList, new TypeToken<List<String>>() {
			}.getType());
			for (final String fid : fidList) {
				if (fid == null || fid.length() <= 0) {
					return ERROR_PARAMETER;
				}
				final Folder folder = this.flm.queryById(fid);
				if (folder == null) {
					return ERROR_PARAMETER;
				}
				if (folder.getFolderParent().equals(locationpath)) {
					continue;
				}
				if (!ConfigureReader.instance().accessFolder(folder, account)) {
					return NO_AUTHORIZED;
				}
				if (!ConfigureReader.instance().authorized(account, AccountAuth.MOVE_FILES,
						fu.getAllFoldersId(folder.getFolderParent()))) {
					return NO_AUTHORIZED;
				}
				if (fid.equals(locationpath) || fu.getParentList(locationpath).parallelStream()
						.anyMatch((e) -> e.getFolderId().equals(folder.getFolderId()))) {
					return ERROR_PARAMETER;
				}
				if (flm.queryByParentId(locationpath).parallelStream()
						.anyMatch((e) -> e.getFolderName().equals(folder.getFolderName()))) {
					if (optMap.get(fid) == null) {
						return ERROR_PARAMETER;
					}
					switch (optMap.get(fid)) {
					case "cover":
						if (!ConfigureReader.instance().authorized(account, AccountAuth.DELETE_FILE_OR_FOLDER,
								fu.getAllFoldersId(locationpath))) {
							return NO_AUTHORIZED;
						}
						Folder f = flm.queryByParentId(locationpath).parallelStream()
								.filter((e) -> e.getFolderName().equals(folder.getFolderName())).findFirst().get();
						Map<String, String> map = new HashMap<>();
						map.put("folderId", folder.getFolderId());
						map.put("locationpath", locationpath);
						if (this.flm.moveById(map) > 0) {
							if (fu.deleteAllChildFolder(f.getFolderId()) > 0) {
								this.lu.writeMoveFileEvent(request, folder);
								break;
							}
						}
						return "cannotMoveFiles";
					case "both":
						if (flm.countByParentId(locationpath) >= FileNodeUtil.MAXIMUM_NUM_OF_SINGLE_FOLDER) {
							return FOLDERS_TOTAL_OUT_OF_LIMIT;
						}
						Map<String, String> map3 = new HashMap<>();
						map3.put("folderId", folder.getFolderId());
						map3.put("locationpath", locationpath);
						if (this.flm.moveById(map3) > 0) {
							Map<String, String> map2 = new HashMap<String, String>();
							map2.put("folderId", folder.getFolderId());
							map2.put("newName", FileNodeUtil.getNewFolderName(folder.getFolderName(),
									flm.queryByParentId(locationpath)));
							if (flm.updateFolderNameById(map2) <= 0) {
								return "cannotMoveFiles";
							}
							this.lu.writeMoveFileEvent(request, folder);
							break;
						}
						this.lu.writeMoveFileEvent(request, folder);
						break;
					case "skip":
						break;
					default:
						return ERROR_PARAMETER;
					}
				} else {
					if (flm.countByParentId(locationpath) >= FileNodeUtil.MAXIMUM_NUM_OF_SINGLE_FOLDER) {
						return FOLDERS_TOTAL_OUT_OF_LIMIT;
					}
					Map<String, String> map = new HashMap<>();
					map.put("folderId", folder.getFolderId());
					map.put("locationpath", locationpath);
					if (this.flm.moveById(map) > 0) {
						this.lu.writeMoveFileEvent(request, folder);
					} else {
						return "cannotMoveFiles";
					}
				}
			}
			if (fidList.size() > 0) {
				ServerInitListener.needCheck = true;
			}
			return "moveFilesSuccess";
		} catch (Exception e) {
			return ERROR_PARAMETER;
		}
	}

	// 移动文件前的确认检查（可视作移动的前置操作）
	@Override
	public String confirmMoveFiles(HttpServletRequest request) {
		final String strIdList = request.getParameter("strIdList");
		final String strFidList = request.getParameter("strFidList");
		final String locationpath = request.getParameter("locationpath");
		final String account = (String) request.getSession().getAttribute("ACCOUNT");
		Folder targetFolder = flm.queryById(locationpath);
		int needMovefilesCount = 0;
		int needMoveFoldersCount = 0;
		if (ConfigureReader.instance().accessFolder(targetFolder, account) && ConfigureReader.instance()
				.authorized(account, AccountAuth.MOVE_FILES, fu.getAllFoldersId(locationpath))) {
			try {
				final List<String> idList = gson.fromJson(strIdList, new TypeToken<List<String>>() {
				}.getType());
				final List<String> fidList = gson.fromJson(strFidList, new TypeToken<List<String>>() {
				}.getType());
				List<Node> repeNodes = new ArrayList<>();
				List<Folder> repeFolders = new ArrayList<>();
				for (final String fileId : idList) {
					if (fileId == null || fileId.length() <= 0) {
						return ERROR_PARAMETER;
					}
					final Node node = this.fm.queryById(fileId);
					if (node == null) {
						return ERROR_PARAMETER;
					}
					if (node.getFileParentFolder().equals(locationpath)) {
						continue;
					}
					if (!ConfigureReader.instance().accessFolder(flm.queryById(node.getFileParentFolder()), account)) {
						return NO_AUTHORIZED;
					}
					if (!ConfigureReader.instance().authorized(account, AccountAuth.MOVE_FILES,
							fu.getAllFoldersId(node.getFileParentFolder()))) {
						return NO_AUTHORIZED;
					}
					if (fm.queryByParentFolderId(locationpath).parallelStream()
							.anyMatch((e) -> e.getFileName().equals(node.getFileName()))) {
						repeNodes.add(node);
					} else {
						needMovefilesCount++;
					}
				}
				for (final String folderId : fidList) {
					if (folderId == null || folderId.length() <= 0) {
						return ERROR_PARAMETER;
					}
					final Folder folder = this.flm.queryById(folderId);
					if (folder == null) {
						return ERROR_PARAMETER;
					}
					if (folder.getFolderParent().equals(locationpath)) {
						continue;
					}
					if (!ConfigureReader.instance().accessFolder(folder, account)) {
						return NO_AUTHORIZED;
					}
					if (!ConfigureReader.instance().authorized(account, AccountAuth.MOVE_FILES,
							fu.getAllFoldersId(folder.getFolderParent()))) {
						return NO_AUTHORIZED;
					}
					if (folderId.equals(locationpath) || fu.getParentList(locationpath).parallelStream()
							.anyMatch((e) -> e.getFolderId().equals(folder.getFolderId()))) {
						return "CANT_MOVE_TO_INSIDE:" + folder.getFolderName();
					}
					if (flm.queryByParentId(locationpath).parallelStream()
							.anyMatch((e) -> e.getFolderName().equals(folder.getFolderName()))) {
						repeFolders.add(folder);
					} else {
						needMoveFoldersCount++;
					}
				}
				long estimateFilesTotal = fm.countByParentFolderId(locationpath) + needMovefilesCount;
				if (estimateFilesTotal > FileNodeUtil.MAXIMUM_NUM_OF_SINGLE_FOLDER || estimateFilesTotal < 0) {
					return FILES_TOTAL_OUT_OF_LIMIT;
				}
				long estimateFoldersTotal = flm.countByParentId(locationpath) + needMoveFoldersCount;
				if (estimateFoldersTotal > FileNodeUtil.MAXIMUM_NUM_OF_SINGLE_FOLDER || estimateFoldersTotal < 0) {
					return FOLDERS_TOTAL_OUT_OF_LIMIT;
				}
				if (repeNodes.size() > 0 || repeFolders.size() > 0) {
					Map<String, List<? extends Object>> repeMap = new HashMap<>();
					repeMap.put("repeFolders", repeFolders);
					repeMap.put("repeNodes", repeNodes);
					return "duplicationFileName:" + gson.toJson(repeMap);
				}
				return "confirmMoveFiles";
			} catch (Exception e) {
				return ERROR_PARAMETER;
			}
		}
		return NO_AUTHORIZED;
	}

	// 上传文件夹的先行检查
	@Override
	public String checkImportFolder(HttpServletRequest request) {
		final String account = (String) request.getSession().getAttribute("ACCOUNT");
		final String folderId = request.getParameter("folderId");
		final String folderName = request.getParameter("folderName");
		final String maxUploadFileSize = request.getParameter("maxSize");
		CheckImportFolderRespons cifr = new CheckImportFolderRespons();
		// 基本文件夹名称合法性检查
		if (folderName == null || folderName.length() == 0) {
			cifr.setResult(ERROR_PARAMETER);
			return gson.toJson(cifr);
		}
		// 上传目标参数检查
		if (folderId == null || folderId.length() == 0) {
			cifr.setResult(ERROR_PARAMETER);
			return gson.toJson(cifr);
		}
		// 检查上传的目标文件夹是否存在
		Folder folder = flm.queryById(folderId);
		if (folder == null) {
			cifr.setResult(ERROR_PARAMETER);
			return gson.toJson(cifr);
		}
		// 先行权限检查
		if (!ConfigureReader.instance().authorized(account, AccountAuth.UPLOAD_FILES, fu.getAllFoldersId(folderId))
				|| !ConfigureReader.instance().authorized(account, AccountAuth.CREATE_NEW_FOLDER,
						fu.getAllFoldersId(folderId))
				|| !ConfigureReader.instance().accessFolder(folder, account)) {
			cifr.setResult(NO_AUTHORIZED);
			return gson.toJson(cifr);
		}
		// 开始文件上传体积限制检查
		try {
			// 获取最大文件体积（以Byte为单位）
			long mufs = Long.parseLong(maxUploadFileSize);
			long pMaxUploadSize = ConfigureReader.instance().getUploadFileSize(account);
			if (pMaxUploadSize >= 0) {
				if (mufs > pMaxUploadSize) {
					cifr.setResult("fileOverSize");
					cifr.setMaxSize(formatMaxUploadFileSize(ConfigureReader.instance().getUploadFileSize(account)));
					return gson.toJson(cifr);
				}
			}
		} catch (Exception e) {
			cifr.setResult(ERROR_PARAMETER);
			return gson.toJson(cifr);
		}
		// 开始文件夹命名冲突检查，若无重名则允许上传。否则检查该文件夹是否具备覆盖条件（具备该文件夹的访问权限且具备删除权限），如无则可选择保留两者或取消
		final List<Folder> folders = flm.queryByParentId(folderId);
		try {
			Folder testFolder = folders.stream().parallel()
					.filter((n) -> n.getFolderName().equals(
							new String(folderName.getBytes(Charset.forName("UTF-8")), Charset.forName("UTF-8"))))
					.findAny().get();
			if (ConfigureReader.instance().accessFolder(testFolder, account) && ConfigureReader.instance()
					.authorized(account, AccountAuth.DELETE_FILE_OR_FOLDER, fu.getAllFoldersId(folderId))) {
				cifr.setResult("repeatFolder_coverOrBoth");
			} else {
				cifr.setResult("repeatFolder_Both");
			}
			return gson.toJson(cifr);
		} catch (NoSuchElementException e) {
			if (flm.countByParentId(folderId) >= FileNodeUtil.MAXIMUM_NUM_OF_SINGLE_FOLDER) {
				// 检查目标文件夹内的文件夹数目是否超限
				cifr.setResult(FOLDERS_TOTAL_OUT_OF_LIMIT);
			} else {
				// 通过所有检查，允许上传
				cifr.setResult("permitUpload");
			}
			return gson.toJson(cifr);
		}
	}

	@Override
	public String doImportFolder(HttpServletRequest request, MultipartFile file) {
		final String account = (String) request.getSession().getAttribute("ACCOUNT");
		String folderId = request.getParameter("folderId");
		final String originalFileName = new String(file.getOriginalFilename().getBytes(Charset.forName("UTF-8")),
				Charset.forName("UTF-8"));
		String folderConstraint = request.getParameter("folderConstraint");
		String newFolderName = request.getParameter("newFolderName");
		// 再次检查上传文件名与目标目录ID
		if (folderId == null || folderId.length() <= 0 || originalFileName == null || originalFileName.length() <= 0) {
			return UPLOADERROR;
		}
		// 检查上传的目标文件夹是否存在
		Folder folder = flm.queryById(folderId);
		if (folder == null) {
			return UPLOADERROR;
		}
		// 检查上传权限
		if (!ConfigureReader.instance().authorized(account, AccountAuth.UPLOAD_FILES, fu.getAllFoldersId(folderId))
				|| !ConfigureReader.instance().authorized(account, AccountAuth.CREATE_NEW_FOLDER,
						fu.getAllFoldersId(folderId))
				|| !ConfigureReader.instance().accessFolder(folder, account)) {
			return UPLOADERROR;
		}
		// 检查上传文件体积是否超限
		long mufs = ConfigureReader.instance().getUploadFileSize(account);
		if (mufs >= 0 && file.getSize() > mufs) {
			return UPLOADERROR;
		}
		// 检查是否具备创建文件夹权限，若有则使用请求中提供的文件夹访问级别，否则使用默认访问级别
		int pc = folder.getFolderConstraint();
		if (folderConstraint != null) {
			try {
				int ifc = Integer.parseInt(folderConstraint);
				if (ifc != 0 && account == null) {
					return UPLOADERROR;
				}
				if (ifc < pc) {
					return UPLOADERROR;
				}
			} catch (Exception e) {
				return UPLOADERROR;
			}
		} else {
			return UPLOADERROR;
		}
		// 计算相对路径的文件夹ID（即真正要保存的文件夹目标）
		String[] paths = getParentPath(originalFileName);
		// 检查上传路径是否正确（必须包含至少一层文件夹）
		if (paths.length == 0) {
			return UPLOADERROR;
		}
		// 若声明了替代文件夹名称，则使用替代文件夹名称作为最上级文件夹名称
		if (newFolderName != null && newFolderName.length() > 0) {
			paths[0] = newFolderName;
		}
		// 执行创建文件夹和上传文件操作
		for (String pName : paths) {
			Folder newFolder;
			try {
				newFolder = fu.createNewFolder(folderId, account, pName, folderConstraint);
			} catch (FoldersTotalOutOfLimitException e1) {
				return FOLDERS_TOTAL_OUT_OF_LIMIT;
			}
			if (newFolder == null) {
				Map<String, String> key = new HashMap<String, String>();
				key.put("parentId", folderId);
				key.put("folderName", pName);
				Folder target = flm.queryByParentIdAndFolderName(key);
				if (target != null) {
					folderId = target.getFolderId();// 向下迭代直至将父路径全部迭代完毕并找到最终路径
				} else {
					return UPLOADERROR;
				}
			} else {
				if (!fu.isValidFolder(newFolder)) {
					return UPLOADERROR;
				}
				folderId = newFolder.getFolderId();
			}
		}
		String fileName = getFileNameFormPath(originalFileName);
		// 检查是否存在同名文件。存在则直接失败（确保上传的文件夹内容的原始性）
		final List<Node> files = this.fm.queryByParentFolderId(folderId);
		if (files.parallelStream().anyMatch((e) -> e.getFileName().equals(fileName))) {
			return UPLOADERROR;
		}
		// 判断上传数目是否超过限额
		if (fm.countByParentFolderId(folderId) >= FileNodeUtil.MAXIMUM_NUM_OF_SINGLE_FOLDER) {
			return FILES_TOTAL_OUT_OF_LIMIT;
		}
		// 将文件存入节点并获取其存入生成路径，型如“UUID.block”形式。
		final File block = this.fbu.saveToFileBlocks(file);
		if (block == null) {
			return UPLOADERROR;
		}
		final String fsize = this.fbu.getFileSize(file);
		final Node f2 = new Node();
		f2.setFileId(UUID.randomUUID().toString());
		if (account != null) {
			f2.setFileCreator(account);
		} else {
			f2.setFileCreator("\u533f\u540d\u7528\u6237");
		}
		f2.setFileCreationDate(ServerTimeUtil.accurateToDay());
		f2.setFileName(fileName);
		f2.setFileParentFolder(folderId);
		f2.setFilePath(block.getName());
		f2.setFileSize(fsize);
		int i = 0;
		// 尽可能避免UUID重复的情况发生，重试10次
		while (true) {
			try {
				if (this.fm.insert(f2) > 0) {
					if (isValidNode(f2)) {
						this.lu.writeUploadFileEvent(request, f2, account);
						return UPLOADSUCCESS;
					} else {
						block.delete();
						return UPLOADERROR;
					}
				}
				break;
			} catch (Exception e) {
				f2.setFileId(UUID.randomUUID().toString());
				i++;
			}
			if (i >= 10) {
				break;
			}
		}
		return UPLOADERROR;
	}

	/**
	 * 
	 * <h2>解析相对路径字符串</h2>
	 * <p>
	 * 根据相对路径获得文件夹的层级名称，并以数组的形式返回。若无层级则返回空数组，若层级名称为空字符串则忽略。
	 * </p>
	 * <p>
	 * 示例1：输入"aaa/bbb/ccc.c"，返回["aaa","bbb"]。
	 * </p>
	 * <p>
	 * 示例2：输入"bbb.c"，返回[]。
	 * </p>
	 * <p>
	 * 示例3：输入"aaa//bbb/ccc.c"，返回["aaa","bbb"]。
	 * </p>
	 * 

	 * @param path
	 *            java.lang.String 原路径字符串
	 * @return java.lang.String[] 解析出的目录层级
	 */
	private String[] getParentPath(String path) {
		if (path != null) {
			String[] paths = path.split("/");
			List<String> result = new ArrayList<String>();
			for (int i = 0; i < paths.length - 1; i++) {
				if (paths[i].length() > 0) {
					result.add(paths[i]);
				}
			}
			return result.toArray(new String[0]);
		}
		return new String[0];
	}

	/**
	 * 
	 * <h2>解析相对路径中的文件名</h2>
	 * <p>
	 * 从相对路径中获得文件名，若解析失败则返回null。
	 * </p>
	 * 

	 * @param java.lang.String
	 *            需要解析的相对路径
	 * @return java.lang.String 文件名
	 */
	private String getFileNameFormPath(String path) {
		if (path != null) {
			String[] paths = path.split("/");
			if (paths.length > 0) {
				return paths[paths.length - 1];
			}
		}
		return null;
	}

	// 再次检查新增的文件是否存在同名问题
	private boolean isValidNode(Node n) {
		Node[] repeats = fm.queryByParentFolderId(n.getFileParentFolder()).parallelStream()
				.filter((e) -> e.getFileName().equals(n.getFileName())).toArray(Node[]::new);
		if (flm.queryById(n.getFileParentFolder()) == null || repeats.length > 1) {
			// 如果插入后存在：
			// 1，该节点没有有效的父级文件夹（死节点）；
			// 2，与同级的其他节点重名，
			// 那么它就是一个无效的节点，应将插入操作撤销
			// 所谓撤销，也就是将该节点的数据立即删除（如果有）
			fm.deleteById(n.getFileId());
			return false;// 返回“无效”的判定结果
		} else {
			return true;// 否则，该节点有效，返回结果
		}
	}
}
