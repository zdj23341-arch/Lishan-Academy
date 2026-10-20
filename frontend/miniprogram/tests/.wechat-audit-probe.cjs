const fs = require('node:fs')
const path = require('node:path')
const automator = require('C:/Users/zerotwo/AppData/Local/Temp/codex-wechat-automator/node_modules/miniprogram-automator')

const route = process.argv[2]
const screenshotPath = process.argv[3]
const port = process.argv[4] || '9424'
const action = process.argv[5]

async function main() {
  const miniProgram = await automator.connect({ wsEndpoint: `ws://127.0.0.1:${port}` })
  miniProgram.on('exception', (error) => console.error('MINIPROGRAM_EXCEPTION', error))

  try {
    if (route === '--close') {
      await miniProgram.close()
      return
    }
    let page
    for (let attempt = 0; attempt < 10 && !page; attempt += 1) {
      try {
        page = await miniProgram.currentPage()
      } catch {
        await new Promise((resolve) => setTimeout(resolve, 500))
      }
    }
    if (!page) throw new Error('No current mini-program page')
    if (route) {
      await miniProgram.reLaunch(route)
      page = await miniProgram.currentPage()
      if (!page) throw new Error(`No page after navigating to ${route}`)
    }
    await page.waitFor(1200)
    if (action === 'expand') {
      const toggle = await page.$('.more-filters-toggle')
      if (!toggle) throw new Error('More-filters control not found')
      await toggle.tap()
      await page.waitFor(300)
    } else if (action === 'open-stage') {
      const picker = await page.$('picker[aria-label="学段"]')
      if (!picker) throw new Error('School-stage picker not found')
      await picker.tap()
      await page.waitFor(300)
    } else if (action === 'select-highschool') {
      const picker = await page.$('picker[aria-label="学段"]')
      if (!picker) throw new Error('School-stage picker not found')
      await picker.trigger('change', { value: 3 })
      await page.waitFor(1200)
    } else if (action === 'open-detail') {
      const card = await page.$('.paper-card')
      if (!card) throw new Error('Paper card not found')
      await card.tap()
      await new Promise((resolve) => setTimeout(resolve, 3000))
      page = await miniProgram.currentPage()
      if (!page) throw new Error('No page after opening paper detail')
    } else if (action === 'scroll-bottom') {
      await miniProgram.pageScrollTo(1600)
      await page.waitFor(400)
    } else if (action === 'check-membership-button') {
      const button = await page.$('.paper-detail-membership-btn')
      console.log('MEMBERSHIP_BUTTON', button ? await button.attribute('class') : 'NOT_FOUND')
      if (!button) throw new Error('Fixed paper membership button not found in runtime')
      await button.tap()
      await new Promise((resolve) => setTimeout(resolve, 3500))
      page = await miniProgram.currentPage()
    }

    const result = {
      stack: (await miniProgram.pageStack()).map((item) => ({ path: item.path, query: item.query })),
      current: { path: page.path, query: page.query, data: await page.data() },
      systemInfo: await miniProgram.systemInfo(),
    }
    console.log(JSON.stringify(result, null, 2))

    if (screenshotPath) {
      fs.mkdirSync(path.dirname(screenshotPath), { recursive: true })
      await miniProgram.screenshot({ path: screenshotPath })
      console.log(`SCREENSHOT ${screenshotPath}`)
    }
  } finally {
    if (route !== '--close') miniProgram.disconnect()
  }
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
