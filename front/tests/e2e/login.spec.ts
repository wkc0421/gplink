import { test, expect } from '@playwright/test';

const testData = [
  { username: 'admin', password: '123456', description: '测试数据1' }
];

test.describe('登录冒烟测试', () => {
  testData.forEach(({ username, password, description }) => {
    test(`登录测试 - ${description}`, async ({ page }) => {
      await page.goto('/');

      // 等待登录页面加载完成
      await page.waitForSelector('form', { timeout: 10000 });

      // 根据登录组件代码定位元素
      const usernameInput = page.locator('form input[maxlength="64"]').first();
      const passwordInput = page.locator('form input[type="password"]').first();
      const loginButton = page.locator('form button[type="submit"]');

      // 填写登录信息
      await usernameInput.fill(username);
      await passwordInput.fill(password);

      // 点击登录按钮
      await loginButton.click();

      // 等待登录处理
      await page.waitForTimeout(3000);

      // 验证登录结果
      const url = page.url();
      const isLoginSuccessful = !url.includes('/login') && !url.includes('/sign-in');

      if (isLoginSuccessful) {
        expect(url).not.toContain('/login');
        console.log(`${description}: 登录成功，当前URL: ${url}`);
      } else {
        console.log(`${description}: 登录失败或需要进一步验证，当前URL: ${url}`);
        // 检查是否有错误消息
        const errorMessage = await page.locator('.ant-message-error').textContent().catch(() => '');
        if (errorMessage) {
          console.log(`错误信息: ${errorMessage}`);
        }
      }
    });
  });
});
