package kohgylw.kiftd.mc;

public class MC {
	/**
	 * 
	 * <h2>主方法</h2>
	 * <p>
	 * 这里是整个kiftd应用的入口，即程序的主方法。您可以从此开始阅读kiftd的全部逻辑代码。
	 * </p>
	 * 

	 * @param args
	 *            String[] 接收控制台传入参数，例如“-console“
	 */
	public static void main(final String[] args) {
		if (args == null || args.length == 0) {
			try {
				UIRunner.build();// 以界面模式启动kiftd。
			} catch (Exception e) {
				// 提示：如果无法以图形界面启动，那么可能是由于资源引用失败或开发环境配置导致的，
				// 您可以根据此处捕获的异常对其进行调试。
				System.out.println(new String(
						"错误！无法以图形界面模式启动网盘系统，您的操作系统可能不支持图形界面。您可以尝试使用命令模式参数“-console”来启动并开始使用kiftd。".getBytes()));
			}
		} else {
			ConsoleRunner.build(args);// 以控制台模式启动kiftd。
		}
	}
}
